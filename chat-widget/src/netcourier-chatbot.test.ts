import { applyStreamEvent, createStreamUrl, type ChatMessage, type StreamEvent } from './netcourier-chatbot';

describe('createStreamUrl', () => {
  it('builds a tenant aware stream url', () => {
    const url = createStreamUrl('/api', 'tenant-1', 'user-2');
    expect(url).toBe('/api/chat/stream?tenantId=tenant-1&userId=user-2');
  });
});

describe('applyStreamEvent', () => {
  it('ignores non-message events', () => {
    const messages: ChatMessage[] = [{ role: 'user', content: 'hello' }];
    const result = applyStreamEvent(messages, { type: 'status', payload: {} });
    expect(result).toBe(messages);
  });

  it('appends assistant messages from stream', () => {
    const messages: ChatMessage[] = [];
    const event: StreamEvent = { type: 'message', payload: { role: 'assistant', content: 'Ready to help!' } };
    const result = applyStreamEvent(messages, event);
    expect(result).toHaveLength(1);
    expect(result[0]).toEqual({ role: 'assistant', content: 'Ready to help!', streaming: undefined });
  });

  it('binds citations to the last assistant message', () => {
    const messages: ChatMessage[] = [{ role: 'assistant', content: 'Summary' }];
    const event: StreamEvent = { type: 'citations', payload: [{ reference: 'Doc · p.1', snippet: 'Details', title: 'Doc' }] };
    const result = applyStreamEvent(messages, event);
    expect(result[0].citations).toEqual([{ reference: 'Doc · p.1', snippet: 'Details', title: 'Doc' }]);
  });
});
