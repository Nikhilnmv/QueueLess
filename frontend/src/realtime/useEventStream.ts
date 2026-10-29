import { useEffect, useRef, useState } from 'react';

export type StreamStatus = 'idle' | 'connecting' | 'live' | 'reconnecting';

type Handlers = Record<string, (data: unknown) => void>;

function parse(data: string): unknown {
  try {
    return JSON.parse(data);
  } catch {
    return data;
  }
}

/**
 * Subscribes to a Server-Sent Events stream with the native EventSource API.
 *
 * - The server sends a `ready` event on every (re)connection; `onReady` then re-fetches the
 *   snapshot, which recovers anything missed while disconnected. Events are treated as hints
 *   ("something changed"), never as the data itself, so a lost or duplicated event is harmless.
 * - EventSource retries network drops by itself. When it gives up (HTTP error, server restart),
 *   we reconnect with exponential backoff and jitter, and immediately when the browser comes back online.
 */
export function useEventStream(url: string | null, handlers: Handlers, onReady?: () => void,
                               onGiveUp?: () => void): StreamStatus {
  const [status, setStatus] = useState<StreamStatus>(url ? 'connecting' : 'idle');
  const handlersRef = useRef(handlers);
  const onReadyRef = useRef(onReady);
  const onGiveUpRef = useRef(onGiveUp);
  handlersRef.current = handlers;
  onReadyRef.current = onReady;
  onGiveUpRef.current = onGiveUp;
  const eventNames = Object.keys(handlers).sort().join(',');

  useEffect(() => {
    if (!url) {
      setStatus('idle');
      return;
    }
    let source: EventSource | null = null;
    let retryTimer: number | undefined;
    let attempt = 0;
    let disposed = false;

    const connect = () => {
      if (disposed) return;
      setStatus(attempt === 0 ? 'connecting' : 'reconnecting');
      source = new EventSource(url, { withCredentials: true });
      source.addEventListener('ready', () => {
        attempt = 0;
        setStatus('live');
        onReadyRef.current?.();
      });
      for (const name of eventNames.split(',').filter(Boolean)) {
        source.addEventListener(name, (event) => handlersRef.current[name]?.(parse((event as MessageEvent).data)));
      }
      source.onerror = () => {
        if (disposed || !source) return;
        if (source.readyState === EventSource.CLOSED) {
          source.close();
          attempt += 1;
          setStatus('reconnecting');
          onGiveUpRef.current?.();
          const delay = Math.min(30_000, 1_000 * 2 ** Math.min(attempt, 5)) + Math.random() * 500;
          retryTimer = window.setTimeout(connect, delay);
        } else {
          setStatus('reconnecting'); // the browser is retrying on its own
        }
      };
    };

    const reconnectNow = () => {
      if (source?.readyState === EventSource.CLOSED) {
        window.clearTimeout(retryTimer);
        attempt = 0;
        connect();
      }
    };

    connect();
    window.addEventListener('online', reconnectNow);
    return () => {
      disposed = true;
      window.clearTimeout(retryTimer);
      window.removeEventListener('online', reconnectNow);
      source?.close();
    };
  }, [url, eventNames]);

  return status;
}
