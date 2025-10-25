import { NetCourierChatbot, type ChatMessage, type StreamEvent } from './netcourier-chatbot';

describe('NetCourierChatbot streaming events', () => {
  it('appends tool_result chunks as assistant updates', () => {
    const element = new NetCourierChatbot();
    (element as any).handleEvent({ type: 'tool_result', text: 'Tool finished' } satisfies StreamEvent);
    const messages = (element as any).messages as ChatMessage[];
    expect(messages).toHaveLength(1);
    expect(messages[0]).toEqual({ role: 'assistant', content: 'Tool finished', streaming: true });
  });

  it('attaches citations on final events', () => {
    const element = new NetCourierChatbot();
    (element as any).handleEvent({
      type: 'final',
      text: 'All done',
      data: {
        citations: [
          { title: 'Doc', reference: 'Doc · p.1', snippet: 'Details' }
        ]
      }
    } satisfies StreamEvent);
    const messages = (element as any).messages as ChatMessage[];
    expect(messages).toHaveLength(1);
    expect(messages[0].citations).toEqual([
      { title: 'Doc', reference: 'Doc · p.1', snippet: 'Details' }
    ]);
    expect((element as any).pending).toBe(false);
  });
});
