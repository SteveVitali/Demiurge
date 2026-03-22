import { WS_BASE_URL, WS_RECONNECT_BASE_MS, WS_RECONNECT_MAX_MS, WS_HEARTBEAT_INTERVAL_MS } from '@/lib/constants';

// Desktop Phase 3 — §7.2: WebSocket connection manager with auto-reconnect.
// Handles: subscribe_logs, unsubscribe_logs, subscribe_agent, unsubscribe_agent, ping/pong.

export type WsStatus = 'connecting' | 'connected' | 'disconnected' | 'error';

export interface WsLogLine {
  type: 'log_line';
  serviceId: string;
  line: string;
  timestamp: string;
}

export interface WsLogBackfill {
  type: 'log_backfill';
  serviceId: string;
  lines: string[];
}

export interface WsAgentMessage {
  type: 'agent_message';
  messageType: 'text' | 'tool_use' | 'tool_result' | 'progress' | 'error';
  data: Record<string, unknown>;
  timestamp: string;
}

export interface WsHeartbeat {
  type: 'heartbeat';
  timestamp: string;
}

export interface WsPong {
  type: 'pong';
  timestamp: string;
}

export type WsIncomingMessage = WsLogLine | WsLogBackfill | WsAgentMessage | WsHeartbeat | WsPong;

export type WsMessageHandler = (message: WsIncomingMessage) => void;
export type WsStatusHandler = (status: WsStatus) => void;

export class DemiurgeWebSocket {
  private ws: WebSocket | null = null;
  private reconnectTimeout: ReturnType<typeof setTimeout> | null = null;
  private heartbeatInterval: ReturnType<typeof setInterval> | null = null;
  private reconnectDelay = WS_RECONNECT_BASE_MS;
  private shouldReconnect = true;
  private onMessage: WsMessageHandler;
  private onStatus: WsStatusHandler;

  // Track active subscriptions for re-subscribe on reconnect
  private logSubscriptions = new Map<string, { runId: string; lines: number }>();
  private agentSubscription: { runId: string } | null = null;

  constructor(onMessage: WsMessageHandler, onStatus: WsStatusHandler) {
    this.onMessage = onMessage;
    this.onStatus = onStatus;
  }

  connect(): void {
    this.shouldReconnect = true;
    this.onStatus('connecting');

    try {
      this.ws = new WebSocket(WS_BASE_URL);

      this.ws.onopen = () => {
        this.reconnectDelay = WS_RECONNECT_BASE_MS;
        this.onStatus('connected');
        this.startHeartbeat();
        this.resubscribe();
      };

      this.ws.onmessage = (e: MessageEvent<string>) => {
        try {
          const msg = JSON.parse(e.data) as WsIncomingMessage;
          this.onMessage(msg);
        } catch {
          // ignore malformed messages
        }
      };

      this.ws.onclose = () => {
        this.cleanup();
        this.onStatus('disconnected');
        if (this.shouldReconnect) {
          this.scheduleReconnect();
        }
      };

      this.ws.onerror = () => {
        this.onStatus('error');
      };
    } catch {
      this.onStatus('error');
      if (this.shouldReconnect) {
        this.scheduleReconnect();
      }
    }
  }

  disconnect(): void {
    this.shouldReconnect = false;
    this.cleanup();
    if (this.ws) {
      this.ws.close();
      this.ws = null;
    }
    this.logSubscriptions.clear();
    this.agentSubscription = null;
    this.onStatus('disconnected');
  }

  // --- Subscription API ---

  subscribeLogs(runId: string, serviceId: string, lines = 500): void {
    this.logSubscriptions.set(serviceId, { runId, lines });
    this.send({
      type: 'subscribe_logs',
      runId,
      serviceId,
      lines,
    });
  }

  unsubscribeLogs(serviceId: string): void {
    this.logSubscriptions.delete(serviceId);
    this.send({
      type: 'unsubscribe_logs',
      serviceId,
    });
  }

  subscribeAgent(runId: string): void {
    this.agentSubscription = { runId };
    this.send({
      type: 'subscribe_agent',
      runId,
    });
  }

  unsubscribeAgent(): void {
    this.agentSubscription = null;
    this.send({
      type: 'unsubscribe_agent',
    });
  }

  get isConnected(): boolean {
    return this.ws?.readyState === WebSocket.OPEN;
  }

  // --- Internal ---

  private send(data: Record<string, unknown>): void {
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(data));
    }
  }

  private resubscribe(): void {
    // Re-subscribe to all active subscriptions after reconnect
    for (const [serviceId, { runId, lines }] of this.logSubscriptions) {
      this.send({ type: 'subscribe_logs', runId, serviceId, lines });
    }
    if (this.agentSubscription) {
      this.send({ type: 'subscribe_agent', runId: this.agentSubscription.runId });
    }
  }

  private startHeartbeat(): void {
    this.stopHeartbeat();
    this.heartbeatInterval = setInterval(() => {
      this.send({ type: 'ping' });
    }, WS_HEARTBEAT_INTERVAL_MS);
  }

  private stopHeartbeat(): void {
    if (this.heartbeatInterval) {
      clearInterval(this.heartbeatInterval);
      this.heartbeatInterval = null;
    }
  }

  private cleanup(): void {
    this.stopHeartbeat();
    if (this.reconnectTimeout) {
      clearTimeout(this.reconnectTimeout);
      this.reconnectTimeout = null;
    }
  }

  private scheduleReconnect(): void {
    this.reconnectTimeout = setTimeout(() => {
      this.reconnectTimeout = null;
      this.connect();
    }, this.reconnectDelay);

    this.reconnectDelay = Math.min(this.reconnectDelay * 2, WS_RECONNECT_MAX_MS);
  }
}
