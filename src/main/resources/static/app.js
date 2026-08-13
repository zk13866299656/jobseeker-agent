'use strict';

function getOrCreate(key, prefix) {
  let value = localStorage.getItem(key);
  if (!value) {
    value = prefix + crypto.randomUUID();
    localStorage.setItem(key, value);
  }
  return value;
}

const userId = getOrCreate('jobagent_user_id', 'user-');
let sessionId = getOrCreate('jobagent_session_id', 'session-');

const chat = document.getElementById('chat');
const chatInner = document.getElementById('chatInner');
const emptyState = document.getElementById('emptyState');
const form = document.getElementById('composer');
const input = document.getElementById('input');
const sendBtn = document.getElementById('sendBtn');
const newSessionBtn = document.getElementById('newSessionBtn');
const menuBtn = document.getElementById('menuBtn');
const sidebar = document.getElementById('sidebar');
const scrim = document.getElementById('scrim');

let streaming = false;

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

function scrollToBottom() {
  chat.scrollTop = chat.scrollHeight;
}

function showEmpty(show) {
  emptyState.style.display = show ? '' : 'none';
}

function addUserMessage(text) {
  showEmpty(false);
  const wrap = el('div', 'message user');
  wrap.appendChild(el('div', 'bubble', text));
  chatInner.appendChild(wrap);
  scrollToBottom();
}

function makeTyping() {
  const dots = el('div', 'typing');
  for (let i = 0; i < 3; i++) dots.appendChild(el('span'));
  return dots;
}

function addAssistantTurn() {
  showEmpty(false);
  const wrap = el('article', 'message assistant');
  const bubble = el('div', 'bubble');

  const answer = el('div', 'answer');
  answer.appendChild(makeTyping());

  const trace = el('details', 'trace');
  trace.appendChild(el('summary', null, '思考过程'));
  const traceBody = el('div', 'trace-body');
  trace.appendChild(traceBody);

  bubble.appendChild(answer);
  bubble.appendChild(trace);
  wrap.appendChild(bubble);
  chatInner.appendChild(wrap);
  scrollToBottom();
  return { answer, trace, traceBody };
}

function send() {
  if (streaming) return;
  const message = input.value.trim();
  if (!message) return;

  input.value = '';
  addUserMessage(message);

  const turn = addAssistantTurn();
  let hasTrace = false;
  streaming = true;
  sendBtn.disabled = true;

  const url = '/api/chat/stream?message=' + encodeURIComponent(message) +
    '&sessionId=' + encodeURIComponent(sessionId) +
    '&userId=' + encodeURIComponent(userId);
  const es = new EventSource(url);

  function finish() {
    if (!hasTrace) turn.trace.remove();
    streaming = false;
    sendBtn.disabled = false;
    es.close();
  }

  es.addEventListener('thinking', e => {
    hasTrace = true;
    turn.traceBody.appendChild(el('div', 'thinking', '思考：' + e.data));
    scrollToBottom();
  });
  es.addEventListener('tool_call', e => {
    hasTrace = true;
    turn.traceBody.appendChild(el('div', 'tool-call', '调用工具：' + e.data));
    scrollToBottom();
  });
  es.addEventListener('tool_result', e => {
    hasTrace = true;
    turn.traceBody.appendChild(el('div', 'tool-result', e.data));
    scrollToBottom();
  });
  es.addEventListener('final_answer', e => {
    turn.answer.textContent = e.data;
    scrollToBottom();
  });
  es.addEventListener('done', finish);
  es.addEventListener('error', e => {
    turn.answer.textContent = e.data || '出错了，请稍后重试';
    turn.answer.classList.add('error');
    finish();
  });
}

function newSession() {
  sessionId = 'session-' + crypto.randomUUID();
  localStorage.setItem('jobagent_session_id', sessionId);
  chatInner.querySelectorAll('.message').forEach(n => n.remove());
  showEmpty(true);
}

function loadHistory() {
  fetch('/api/chat/history?sessionId=' + encodeURIComponent(sessionId))
    .then(res => res.json())
    .then(messages => {
      messages.forEach(m => {
        if (m.role === 'user') {
          addUserMessage(m.content);
        } else if (m.role === 'assistant') {
          const turn = addAssistantTurn();
          turn.answer.textContent = m.content;
          turn.trace.remove();
        }
      });
      showEmpty(messages.length === 0);
    })
    .catch(() => {});
}

function openSidebar() {
  sidebar.classList.add('open');
  scrim.classList.add('show');
}

function closeSidebar() {
  sidebar.classList.remove('open');
  scrim.classList.remove('show');
}

form.addEventListener('submit', e => {
  e.preventDefault();
  send();
});
newSessionBtn.addEventListener('click', () => {
  newSession();
  closeSidebar();
});
menuBtn.addEventListener('click', () => {
  sidebar.classList.contains('open') ? closeSidebar() : openSidebar();
});
scrim.addEventListener('click', closeSidebar);
document.querySelectorAll('.quick-card').forEach(card => {
  card.addEventListener('click', () => {
    input.value = card.dataset.prompt;
    input.focus();
    closeSidebar();
  });
});

loadHistory();
