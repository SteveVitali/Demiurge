import { API_BASE_URL, SSE_RECONNECT_BASE_MS, SSE_RECONNECT_MAX_MS } from '@/lib/constants';
import type { SystemEvent } from './types';

export type SSEEventHandler = (event: SystemEvent) => void;
export type SSEStatusHandler = (status: 'connecting' | 'connected' | 'disconnected' | 'error') => void;

export class SSEClient {
  private eventSource: EventSource | null = null;
  private reconnectTimeout: ReturnType<typeof setTimeout> | null = null;
  private reconnectDelay = SSE_RECONNECT_BASE_MS;
  private shouldReconnect = true;
  private runId: string;
  private onEvent: SSEEventHandler;
  private onStatus: SSEStatusHandler;

  constructor(runId: string, onEvent: SSEEventHandler, onStatus: SSEStatusHandler) {
    this.runId = runId;
    this.onEvent = onEvent;
    this.onStatus = onStatus;
  }

  connect(): void {
    this.shouldReconnect = true;
    this.onStatus('connecting');

    const url = `${API_BASE_URL}/runs/${this.runId}/events`;
    this.eventSource = new EventSource(url);

    this.eventSource.onopen = () => {
      this.reconnectDelay = SSE_RECONNECT_BASE_MS;
      this.onStatus('connected');
    };

    this.eventSource.onmessage = (e: MessageEvent<string>) => {
      try {
        const event = JSON.parse(e.data) as SystemEvent;
        this.onEvent(event);
      } catch {
        // ignore malformed events
      }
    };

    this.eventSource.addEventListener('done', () => {
      this.shouldReconnect = false;
      this.disconnect();
    });

    this.eventSource.onerror = () => {
      this.onStatus('error');
      this.eventSource?.close();
      this.eventSource = null;

      if (this.shouldReconnect) {
        this.scheduleReconnect();
      }
    };
  }

  disconnect(): void {
    this.shouldReconnect = false;
    if (this.reconnectTimeout) {
      clearTimeout(this.reconnectTimeout);
      this.reconnectTimeout = null;
    }
    if (this.eventSource) {
      this.eventSource.close();
      this.eventSource = null;
    }
    this.onStatus('disconnected');
  }

  private scheduleReconnect(): void {
    this.reconnectTimeout = setTimeout(() => {
      this.reconnectTimeout = null;
      this.connect();
    }, this.reconnectDelay);

    this.reconnectDelay = Math.min(this.reconnectDelay * 2, SSE_RECONNECT_MAX_MS);
  }
}
