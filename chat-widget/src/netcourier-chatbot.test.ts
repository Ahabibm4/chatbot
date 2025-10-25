import { NetCourierChatbot } from './netcourier-chatbot';

describe('NetCourierChatbot streaming events', () => {
  it('merges partial frames into a single streaming message', () => {
    const element = new NetCourierChatbot();
    element['handleEvent']({ type: 'partial', text: 'Hello' });
    element['handleEvent']({ type: 'partial', text: 'Hello world' });
    const messages = element['messages'];
    expect(messages).toHaveLength(1);
    expect(messages[0]).toEqual({ role: 'assistant', content: 'Hello world', streaming: true });
  });

  it('appends tool_result chunks as assistant updates', () => {
    const element = new NetCourierChatbot();
    element['handleEvent']({ type: 'tool_result', text: 'Tool finished' });
    const messages = element['messages'];
    expect(messages).toHaveLength(1);
    expect(messages[0]).toEqual({ role: 'assistant', content: 'Tool finished', streaming: true });
  });

  it('attaches citations on final events', () => {
    const element = new NetCourierChatbot();
    element['handleEvent']({
      type: 'final',
      text: 'All done',
      metadata: {
        citations: [
          { title: 'Doc', reference: 'Doc · p.1', snippet: 'Details' }
        ]
      }
    });
    const messages = element['messages'];
    expect(messages).toHaveLength(1);
    expect(messages[0].citations).toEqual([
      { title: 'Doc', reference: 'Doc · p.1', snippet: 'Details' }
    ]);
    expect(messages[0].streaming).toBe(false);
    expect(element['pending']).toBe(false);
  });

  it('processes NDJSON buffers with partial updates in order', () => {
    const element = new NetCourierChatbot();
    const payload = [
      { type: 'partial', text: 'Hi' },
      { type: 'partial', text: 'Hi there' },
      { type: 'final', text: 'Hi there', metadata: { citations: [] } }
    ]
      .map(frame => JSON.stringify(frame))
      .join('\n') + '\n';

    const remainder = element['processBuffer'](payload);
    expect(remainder).toBe('');

    const messages = element['messages'];
    expect(messages).toHaveLength(1);
    expect(messages[0].content).toBe('Hi there');
    expect(messages[0].streaming).toBe(false);
  });
});
