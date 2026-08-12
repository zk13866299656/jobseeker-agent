const sessionId = 'demo-' + Date.now();

function addLine(cls, text) {
    const div = document.createElement('div');
    div.className = cls;
    div.textContent = text;
    document.getElementById('chat').appendChild(div);
}

function send() {
    const input = document.getElementById('input');
    const msg = input.value.trim();
    if (!msg) return;
    input.value = '';
    addLine('user', '我：' + msg);

    const url = '/api/chat/stream?message=' + encodeURIComponent(msg) + '&sessionId=' + sessionId;
    const es = new EventSource(url);

    es.addEventListener('thinking', e => addLine('thinking', '思考：' + e.data));
    es.addEventListener('tool_call', e => addLine('tool_call', '调用工具：' + e.data));
    es.addEventListener('tool_result', e => addLine('tool_result', '工具结果：\n' + e.data));
    es.addEventListener('final_answer', e => addLine('final_answer', '回答：' + e.data));
    es.addEventListener('done', () => es.close());
    es.addEventListener('error', () => {
        addLine('error', '出错了，请稍后重试');
        es.close();
    });
}
