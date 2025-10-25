import { LitElement, css, html } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';

export type Citation = {
  docId?: string;
  title: string;
  reference: string;
  snippet: string;
};

export type ChatMessage = {
  role: 'user' | 'assistant';
  content: string;
  streaming?: boolean;
  citations?: Citation[];
};

export type StreamEvent = {
  type: string;
  text?: string;
  data?: any;
};

type TranslationStrings = {
  header: string;
  placeholder: string;
  send: string;
  dropPrompt: string;
  uploading: string;
  guardrail: string;
};

@customElement('nc-chatbot')
export class NetCourierChatbot extends LitElement {
  @property({ type: String, attribute: 'api-base' }) apiBase = '/api';
  @property({ type: String, attribute: 'tenant-id' }) tenantId = '';
  @property({ type: String, attribute: 'user-id' }) userId = '';
  @property({ type: Array }) roles: string[] = [];
  @property({ type: String }) ui: string | null = null;
  @property({ type: String }) locale = 'en';
  @property({ type: String }) theme: 'light' | 'dark' | string = 'light';
  @property({ type: String }) brand = 'NetCourier Assistant';
  @property({ type: Boolean, attribute: 'allow-uploads' }) allowUploads = false;
  @property({ type: Object }) translations: Partial<TranslationStrings> = {};

  @state() private messages: ChatMessage[] = [];
  @state() private pending = false;
  @state() private controller: AbortController | null = null;
  @state() private guardrailNotice: string | null = null;
  @state() private uploading = false;
  @state() private dropActive = false;
  private sessionIdValue: string | null = null;

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
      position: relative;
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
      position: relative;
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

    .bubble.assistant ol.citations {
      margin: 8px 0 0;
      padding-left: 18px;
      font-size: 0.75rem;
      color: var(--nc-citation-color, rgba(0, 0, 0, 0.6));
    }

    .bubble.assistant ol.citations li {
      margin-bottom: 4px;
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

    .drop-overlay {
      position: absolute;
      inset: 0;
      border: 2px dashed var(--nc-accent, #2563eb);
      border-radius: 12px;
      display: flex;
      align-items: center;
      justify-content: center;
      background: rgba(37, 99, 235, 0.08);
      color: var(--nc-accent, #2563eb);
      font-weight: 600;
      pointer-events: none;
    }

    .guardrail {
      padding: 8px 16px;
      background: rgba(251, 191, 36, 0.2);
      color: #92400e;
      font-size: 0.8rem;
      text-align: center;
    }
  `;

  private get strings(): TranslationStrings {
    const defaults: TranslationStrings = {
      header: this.brand,
      placeholder: 'Ask me anything',
      send: 'Send',
      dropPrompt: 'Drop files to ingest',
      uploading: 'Uploading…',
      guardrail: 'Response constrained for safety'
    };
    return { ...defaults, ...this.translations, header: this.translations.header ?? this.brand } as TranslationStrings;
  }

  private get ingestUrl(): string {
    const base = this.apiBase.replace(/\/$/, '').replace(/\/api$/, '');
    return `${base}/admin/ingest/upload`;
  }

  render() {
    const strings = this.strings;
    return html`
      <header>
        <span>${strings.header}</span>
        ${this.pending ? html`<span>•••</span>` : null}
      </header>
      ${this.guardrailNotice
        ? html`<div class="guardrail">${strings.guardrail}: ${this.guardrailNotice}</div>`
        : null}
      <main>
        <div class="messages" @dragover=${this.onDragOver} @dragleave=${this.onDragLeave} @drop=${this.onDrop}>
          ${this.messages.map(message => html`
            <div class="bubble ${message.role}">
              ${message.content}
              ${message.role === 'assistant' && message.citations?.length
                ? html`<ol class="citations">
                    ${message.citations.map((citation, index) => html`<li><strong>[${index + 1}] ${citation.reference}</strong> — ${citation.snippet}</li>`)}
                  </ol>`
                : null}
            </div>
          `)}
        </div>
        ${this.allowUploads && (this.dropActive || this.uploading)
          ? html`<div class="drop-overlay">${this.uploading ? strings.uploading : strings.dropPrompt}</div>`
          : null}
        <form @submit=${this.onSubmit}>
          <input type="text" name="message" placeholder="${strings.placeholder}" ?disabled=${this.pending} />
          <button type="submit" ?disabled=${this.pending}>${strings.send}</button>
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
    this.guardrailNotice = null;
    this.sendMessage(message);
  }

  private appendMessage(message: ChatMessage) {
    this.messages = [...this.messages, message];
    this.updateComplete.then(() => {
      const container = this.renderRoot.querySelector('.messages');
      container?.scrollTo({ top: container.scrollHeight, behavior: 'smooth' });
    });
  }

  private getSessionId(): string {
    if (!this.sessionIdValue) {
      if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
        this.sessionIdValue = crypto.randomUUID();
      } else {
        this.sessionIdValue = `session-${Math.random().toString(16).slice(2)}`;
      }
    }
    return this.sessionIdValue;
  }

  private processBuffer(buffer: string): string {
    let working = buffer;
    let newlineIndex = working.indexOf('\n');
    while (newlineIndex >= 0) {
      const line = working.slice(0, newlineIndex).trim();
      working = working.slice(newlineIndex + 1);
      if (line) {
        try {
          const event = JSON.parse(line) as StreamEvent;
          this.handleEvent(event);
        } catch (error) {
          console.error('Failed to parse NDJSON chunk', error);
        }
      }
      newlineIndex = working.indexOf('\n');
    }
    return working;
  }

  private attachCitations(citations: Citation[]) {
    if (!this.messages.length) {
      return;
    }
    const lastIndex = this.messages.length - 1;
    const last = this.messages[lastIndex];
    if (last.role !== 'assistant') {
      return;
    }
    const updated = { ...last, citations };
    this.messages = [...this.messages.slice(0, lastIndex), updated];
  }

  private onDragOver(event: DragEvent) {
    if (!this.allowUploads) {
      return;
    }
    event.preventDefault();
    this.dropActive = true;
  }

  private onDragLeave(event: DragEvent) {
    if (!this.allowUploads) {
      return;
    }
    if (event.currentTarget && event.relatedTarget && (event.currentTarget as HTMLElement).contains(event.relatedTarget as Node)) {
      return;
    }
    this.dropActive = false;
  }

  private onDrop(event: DragEvent) {
    if (!this.allowUploads) {
      return;
    }
    event.preventDefault();
    this.dropActive = false;
    const files = event.dataTransfer?.files;
    if (files && files.length) {
      void this.uploadFiles(files);
    }
  }

  private async uploadFiles(files: FileList) {
    if (!this.tenantId) {
      console.warn('Uploads require a tenant-id attribute');
      return;
    }
    this.uploading = true;
    try {
      for (const file of Array.from(files)) {
        const form = new FormData();
        form.append('tenantId', this.tenantId);
        form.append('title', file.name);
        if (this.roles?.length) {
          this.roles.forEach(role => form.append('roles', role));
        }
        form.append('file', file, file.name);
        await fetch(this.ingestUrl, {
          method: 'POST',
          body: form,
          credentials: 'include'
        });
      }
    } catch (error) {
      console.error('Failed to upload documents', error);
    } finally {
      this.uploading = false;
    }
  }

  private async sendMessage(content: string) {
    if (this.pending) {
      this.controller?.abort();
    }
    this.pending = true;
    const controller = new AbortController();
    this.controller = controller;

    const submission = {
      sessionId: this.getSessionId(),
      message: content,
      context: {
        tenantId: this.tenantId,
        userId: this.userId,
        ui: this.ui ?? 'CP',
        locale: this.locale ?? 'en',
        roles: this.roles?.length ? this.roles : undefined
      }
    };

    try {
      const response = await fetch(`${this.apiBase}/chat`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(submission),
        signal: controller.signal,
        credentials: 'include'
      });

      if (!response.ok) {
        throw new Error(`Chat request failed with status ${response.status}`);
      }

      const body = response.body;
      if (!body) {
        throw new Error('Missing response body');
      }

      const reader = body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';

      while (true) {
        const { value, done } = await reader.read();
        if (value) {
          buffer += decoder.decode(value, { stream: !done });
          buffer = this.processBuffer(buffer);
        }
        if (done) {
          buffer += decoder.decode();
          this.processBuffer(buffer);
          break;
        }
      }
    } catch (error) {
      if ((error as DOMException)?.name === 'AbortError') {
        return;
      }
      console.error('Chat request failed', error);
    } finally {
      this.pending = false;
      this.controller = null;
    }
  }

  private handleEvent(event: StreamEvent) {
    switch (event.type) {
      case 'thinking':
        return;
      case 'tool_result':
        if (event.text) {
          this.appendMessage({ role: 'assistant', content: event.text, streaming: true });
        }
        return;
      case 'final': {
        const data = event.data ?? {};
        const guardrailAction = data.guardrailAction as string | undefined;
        if (guardrailAction && guardrailAction !== 'ALLOW') {
          this.guardrailNotice = guardrailAction;
        }
        if (event.text) {
          this.appendMessage({ role: 'assistant', content: event.text });
        }
        const citations = Array.isArray(data.citations) ? (data.citations as Citation[]) : [];
        if (citations.length) {
          this.attachCitations(citations);
        }
        this.pending = false;
        this.controller = null;
        return;
      }
      default:
        if (event.data && Array.isArray(event.data.citations)) {
          this.attachCitations(event.data.citations as Citation[]);
        }
    }
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'nc-chatbot': NetCourierChatbot;
  }
}
