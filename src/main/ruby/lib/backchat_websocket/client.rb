# -*- encoding: utf-8 -*-

module Backchat
  module WebSocket

    RECONNECT_SCHEDULE = [1, 1, 1, 1, 1, 5, 5, 5, 5, 5, 10, 10, 10, 10, 10, 30, 30, 30, 30, 30, 60, 60, 60, 60, 60, 300, 300, 300, 300, 300]
    JOURNAL_PATH = "./logs/journal.log"
    EVENT_NAMES = {
      :receive => "message",
      :connect => "open",
      :disconnect => "close"
    }

    class Client

      attr_reader :uri, :retry_schedule

      def initialize(options={})
        options = {:uri => options} if options.is_a?(String)
        raise Backchat::WebSocket::UriRequiredError, ":uri parameter is required" unless options.key?(:uri)
        parsed = begin
          u = Addressable::URI.parse(options[:uri].gsub(/^http/i, 'ws')).normalize
          u.path = "/" if u.path.nil? || u.path.strip.empty?
          u.to_s
        rescue 
          raise Backchat::WebSocket::InvalidURIError, ":uri [#{options[:uri]}] must be a valid uri" 
        end
        @uri, @retry_schedule = parsed, (options[:retry_schedule]||RECONNECT_SCHEDULE.clone)
        @retry_indefinitely = options[:retry_indefinitely]||true
        @state = :disconnected
        if !!options[:journaled]
          @journal = File.open(JOURNAL_PATH, 'a')
          @journal_buffer = []
        end
      end

      def send(msg)
        m = msg.is_a?(String) ? msg : msg.to_json
        if connected?
          while entry = (@journal_buffer||[]).shift
            @ws.send(line)
          end
          @ws.send(m)
        elsif @state == :journal_redo
          @journal_buffer << m 
        else
          @journal.puts(m) if journaled?
        end
      end

      def connect
        establish_connection unless @state == :connecting || @state == :connected
      end

      def on(event, &callback) 
        cache_handler(event.to_sym, callback)
      end

      def remove_on(event, &callback)
        evict_handler(event.to_sym, callback)
      end

      def connected?
        @state == :connected
      end

      def journaled?
        !!@journal
      end

      def disconnect
        if @state == :connected || @state == :connecting
          @skip_reconnect = true
          @ws.close
        end
      end

      def method_missing(name, *args, &block)
        if name =~ /^on_(.+)$/ 
          on($1, &block)
        elsif name =~ /^remove_on_(.+)$/
          remove_on($1, &block)
        else
          super
        end
      end

      private
        def reconnect
          unless @skip_reconnect          
            unless @retries.nil? || @retries.empty?
              retry_in = @retries.shift 
              secs = "second#{retry_in == 1 ? "" : "s"}"
              puts "connection lost, reconnecting in #{retry_in >= 1 ? retry_in : retry_in * 1000} #{retry_in >= 1 ? secs : "millis"}"
              EM.add_timer(retry_in) { establish_connection }
            else 
              if @retry_indefinitely
                raise ServerDisconnectedError, "Exhausted the retry schedule. The server at #{uri} is just not there."
              else
                retry_in = @retry_schedule.last
                secs = "second#{retry_in == 1 ? "" : "s"}"
                puts "connection lost, reconnecting in #{retry_in >= 1 ? retry_in : retry_in * 1000} #{retry_in >= 1 ? secs : "millis"}"
                EM.add_timer(retry_in) { establish_connection }
              end
            end
          else
            @state == :disconnected
          end
        end

        def establish_connection
          unless connected?
            begin
              @ws = Faye::WebSocket::Client.new(@uri)
              @state == :connecting
              @skip_reconnect = false

              @ws.onopen = lambda { |e| 
                puts "connected to #{uri}"
                @state = journaled? && @state == :reconnecting ? :journal_redo : :connected
                flush_journal_to_server if @state == :journal_redo
                @retries = @retry_schedule.clone
                notify_handlers(:connected, e)
              }
              @ws.onmessage = lambda { |e|
                notify_handlers(:receive, e)
              }
              @ws.onerror = lambda { |e| 
                puts e.inspect
                notify_handlers(:error, e)
              }
              @ws.onclose = lambda { |e| 
                @state = @skip_reconnect ? :disconnecting : :reconnecting
                if @state == :disconnecting
                  notify_handlers(:disconnected, e)
                else
                  notify_handlers(:reconnect, e)
                end
                reconnect 
              }
            rescue Exception => e
              puts e
            end
          end
        end

        def notify_handlers(evt, arg)
          (@handlers[evt.to_sym]||[]).each { |h| h.call(arg.data) }
        end

        def flush_journal_to_server
          @journal.close
          IO.foreach(JOURNAL_PATH) do |line|
            @ws.send(line)
          end
          while entry = (@journal_buffer||[]).shift
            @ws.send(line)
          end
          @state = :connected
          @journal = File.open(JOURNAL_PATH, 'w')
        end

        def cache_handler(evt, listener) 
          @handlers ||= {}
          @handlers[evt] ||= []
          @handlers[evt].push listener 
        end

        def evict_handler(evt, listener) 
          @handlers ||= {}
          @handlers[evt] = (handlers[evt]||[]).reject { |l| l == listener }
        end

    end
  end
end