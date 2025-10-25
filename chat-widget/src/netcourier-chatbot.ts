import { LitElement, css, html } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';

type ChatMessage = {
  role: 'user' | 'assistant';
  content: string;
  streaming?: boolean;
};

type StreamEvent = {
  type: string;
  payload: unknown;
};

@customElement('nc-chatbot')
export class NetCourierChatbot extends LitElement {
  @property({ type: String, attribute: 'api-base' }) apiBase = '/api';
  @property({ type: String, attribute: 'tenant-id' }) tenantId = '';
  @property({ type: String, attribute: 'user-id' }) userId = '';
  @property({ type: String }) ui: string | null = null;
  @property({ type: String }) locale = 'en';
  @property({ type: String }) theme: 'light' | 'dark' | string = 'light';

  @state() private messages: ChatMessage[] = [];
  @state() private pending = false;
  @state() private controller: AbortController | null = null;

  static styles = css`
    :host {
      display: block;
      font-family: var(--nc-font-family, 'Inter', system-ui, sans-serif);
      border: 1px solid var(--nc-border-color, rgba(0, 0, 0, 0.1));
      border-radius: var(--nc-radius, 16px);
      overflow: hidden;
      background: var(--nc-bg, #fff);
      color: var(--nc-fg, #111);
      width: 360px;
      height: 480px;
      box-shadow: var(--nc-shadow, 0 12px 32px rgba(0, 0, 0, 0.12));
    }

    :host([theme='dark']) {
      --nc-bg: #111827;
      --nc-fg: #f9fafb;
      --nc-border-color: rgba(255, 255, 255, 0.08);
    }

    header {
      padding: 16px;
      display: flex;
      align-items: center;
      justify-content: space-between;
      border-bottom: 1px solid var(--nc-border-color, rgba(0, 0, 0, 0.1));
      font-weight: 600;
      text-transform: uppercase;
      font-size: 0.75rem;
      letter-spacing: 0.08em;
    }

    main {
      display: flex;
      flex-direction: column;
      height: calc(100% - 64px);
    }

    .messages {
      flex: 1;
      overflow-y: auto;
      padding: 16px;
      gap: 12px;
      display: flex;
      flex-direction: column;
      background: var(--nc-messages-bg, transparent);
    }

    .bubble {
      padding: 12px 16px;
      border-radius: 12px;
      max-width: 90%;
      line-height: 1.4;
      font-size: 0.9rem;
    }

    .bubble.user {
      align-self: flex-end;
      background: var(--nc-user-bg, #2563eb);
      color: #fff;
    }

    .bubble.assistant {
      align-self: flex-start;
      background: var(--nc-assistant-bg, rgba(0, 0, 0, 0.05));
    }

    form {
      display: flex;
      padding: 12px 16px;
      border-top: 1px solid var(--nc-border-color, rgba(0, 0, 0, 0.1));
      gap: 8px;
      background: inherit;
    }

    input[type='text'] {
      flex: 1;
      border-radius: 999px;
      border: 1px solid var(--nc-border-color, rgba(0, 0, 0, 0.2));
      padding: 10px 16px;
      font-size: 0.9rem;
      background: inherit;
      color: inherit;
    }

    button[type='submit'] {
      border-radius: 999px;
      border: none;
      background: var(--nc-accent, #2563eb);
      color: white;
      font-weight: 600;
      padding: 0 20px;
      cursor: pointer;
      transition: opacity 0.2s ease;
    }

    button[type='submit'][disabled] {
      opacity: 0.5;
      cursor: not-allowed;
    }
  `;

  render() {
    return html`
      <header>
        <span>NetCourier Assistant</span>
        ${this.pending ? html`<span>•••</span>` : null}
      </header>
      <main>
        <div class="messages">
          ${this.messages.map(message => html`
            <div class="bubble ${message.role}">${message.content}</div>
          `)}
        </div>
        <form @submit=${this.onSubmit}>
          <input type="text" name="message" placeholder="Ask me anything" ?disabled=${this.pending} />
          <button type="submit" ?disabled=${this.pending}>Send</button>
        </form>
      </main>
    `;
  }

  private onSubmit(event: Event) {
    event.preventDefault();
    const form = event.target as HTMLFormElement;
    const input = form.elements.namedItem('message') as HTMLInputElement;
    if (!input.value) {
      return;
    }
    this.appendMessage({ role: 'user', content: input.value });
    const message = input.value;
    input.value = '';
    this.sendMessage(message);
  }

  private appendMessage(message: ChatMessage) {
    this.messages = [...this.messages, message];
    this.updateComplete.then(() => {
      const container = this.renderRoot.querySelector('.messages');
      container?.scrollTo({ top: container.scrollHeight, behavior: 'smooth' });
    });
  }

  private sendMessage(content: string) {
    if (this.pending) {
      this.controller?.abort();
    }
    this.pending = true;
    const conversationId = crypto.randomUUID();
    const controller = new AbortController();
    this.controller = controller;
    const source = new EventSource(this.buildStreamUrl(), { withCredentials: true });
    source.onmessage = event => {
      try {
        const payload = JSON.parse(event.data) as StreamEvent;
        this.handleEvent(payload);
      } catch (error) {
        console.error('Failed to parse stream payload', error);
      }
    };
    source.onerror = () => {
      source.close();
      this.pending = false;
    };

    fetch(`${this.apiBase}/chat`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        conversationId,
        tenantId: this.tenantId,
        userId: this.userId,
        context: { ui: this.ui, locale: this.locale },
        turns: [
          { role: 'USER', content }
        ]
      }),
      signal: controller.signal
    })
      .then(response => response.json())
      .then(json => {
        if (json?.messages) {
          const assistant = json.messages.find((message: any) => message.role === 'ASSISTANT');
          if (assistant) {
            this.appendMessage({ role: 'assistant', content: assistant.content });
          }
        }
      })
      .catch(error => console.error('Chat request failed', error))
      .finally(() => {
        this.pending = false;
        source.close();
      });
  }

  private handleEvent(event: StreamEvent) {
    if (event.type === 'message') {
      const payload = event.payload as ChatMessage;
      this.appendMessage({ role: 'assistant', content: payload.content, streaming: payload.streaming });
    }
  }

  private buildStreamUrl() {
    const params = new URLSearchParams({ tenantId: this.tenantId, userId: this.userId });
    return `${this.apiBase}/chat/stream?${params.toString()}`;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'nc-chatbot': NetCourierChatbot;
  }
}
