/**
 * ChimpaCast - Frontend WebRTC & WebSocket Client
 * Plataforma de compartilhamento de tela de alta performance para primatas
 */

function getOrCreateUserId() {
    let uid = sessionStorage.getItem('chimpacast_uid');
    if (!uid) {
        uid = 'user-' + Math.random().toString(36).substring(2, 9);
        sessionStorage.setItem('chimpacast_uid', uid);
    }
    return uid;
}

const state = {
    userId: getOrCreateUserId(),
    username: '',
    room: 'principal',
    sessionToken: readStored('chimpacast_token'),
    discordUser: loadStoredDiscordUser(),
    quotaAvailable: true,
    authFailed: false,
    ws: null,
    wsPingInterval: null,
    isSharing: false,
    localStream: null,
    publisherPc: null,
    publisherSessionId: null,
    publishedTracks: null,
    publishedQuality: null,
    // Mapa de inscrições ativas: userId -> { pc, sessionId, publisherSessionId, username, cancelled }
    subscriptions: new Map(),
    participants: [],
    unreadMessages: 0,
    chatOpen: true,
    allowRemoteScreenKick: true
};

// Elementos DOM
const dom = {
    modal: document.getElementById('join-modal'),
    joinForm: document.getElementById('join-form'),
    usernameInput: document.getElementById('username-input'),
    roomInput: document.getElementById('room-input'),
    joinErrorMsg: document.getElementById('join-error-msg'),
    app: document.getElementById('app'),
    displayRoomName: document.getElementById('display-room-name'),
    displayUsername: document.getElementById('display-username'),
    statusText: document.getElementById('status-text'),
    qualityDot: document.getElementById('quality-dot'),
    qualityText: document.getElementById('quality-text'),
    emptyState: document.getElementById('empty-state'),
    screensGrid: document.getElementById('screens-grid'),
    shareBtn: document.getElementById('share-btn'),
    shareBtnText: document.getElementById('share-btn-text'),
    emptyShareBtn: document.getElementById('empty-share-btn'),
    qualitySelect: document.getElementById('quality-select'),
    fullscreenBtn: document.getElementById('fullscreen-btn'),
    leaveBtn: document.getElementById('leave-btn'),
    chatSidebar: document.getElementById('chat-sidebar'),
    toggleChatBtn: document.getElementById('toggle-chat-btn'),
    openParticipantsBtn: document.getElementById('open-participants-btn'),
    participantsModal: document.getElementById('participants-modal'),
    closeParticipantsModalBtn: document.getElementById('close-participants-modal'),
    participantsList: document.getElementById('participants-list'),
    modalParticipantsSubtitle: document.getElementById('modal-participants-subtitle'),
    chatMessages: document.getElementById('chat-messages'),
    chatForm: document.getElementById('chat-form'),
    chatInput: document.getElementById('chat-input'),
    participantCount: document.getElementById('participant-count'),
    unreadBadge: document.getElementById('unread-badge'),
    quotaWidget: document.getElementById('quota-widget'),
    quotaPercent: document.getElementById('quota-percent'),
    quotaRemaining: document.getElementById('quota-remaining'),
    quotaBarFill: document.getElementById('quota-bar-fill'),
    quotaWarningBanner: document.getElementById('quota-warning-banner'),
    warningBannerText: document.getElementById('warning-banner-text'),
    closeWarningBannerBtn: document.getElementById('close-warning-banner'),
    cutoffModal: document.getElementById('cutoff-modal'),
    cutoffReasonText: document.getElementById('cutoff-reason-text'),
    btnCutoffOk: document.getElementById('btn-cutoff-ok'),
    inactivityModal: document.getElementById('inactivity-modal'),
    inactivityCountdownNum: document.getElementById('inactivity-countdown-num'),
    btnInactivityStay: document.getElementById('btn-inactivity-stay'),
    btnInactivityStop: document.getElementById('btn-inactivity-stop')
};

// ==========================================
// 0. Efeitos Sonoros Estilo Discord (Web Audio API)
// ==========================================

const soundManager = {
    ctx: null,
    init() {
        if (!this.ctx) {
            const AudioContext = window.AudioContext || window.webkitAudioContext;
            if (AudioContext) {
                this.ctx = new AudioContext();
            }
        }
        if (this.ctx && this.ctx.state === 'suspended') {
            this.ctx.resume().catch(() => {});
        }
    },
    // Som agradável ascendente estilo Discord (Início de Transmissão)
    playScreenShareStart() {
        try {
            this.init();
            if (!this.ctx) return;
            const now = this.ctx.currentTime;
            const notes = [
                { freq: 587.33, time: 0, duration: 0.14 },    // D5
                { freq: 783.99, time: 0.08, duration: 0.16 },  // G5
                { freq: 987.77, time: 0.16, duration: 0.28 }   // B5
            ];

            notes.forEach(n => {
                const osc = this.ctx.createOscillator();
                const gain = this.ctx.createGain();

                osc.type = 'sine';
                osc.frequency.setValueAtTime(n.freq, now + n.time);

                gain.gain.setValueAtTime(0.0001, now + n.time);
                gain.gain.linearRampToValueAtTime(0.18, now + n.time + 0.02);
                gain.gain.exponentialRampToValueAtTime(0.0001, now + n.time + n.duration);

                osc.connect(gain);
                gain.connect(this.ctx.destination);

                osc.start(now + n.time);
                osc.stop(now + n.time + n.duration);
            });
        } catch (e) {
            console.debug("Erro no sintetizador de áudio:", e);
        }
    },
    // Som agradável descendente estilo Discord (Fim de Transmissão)
    playScreenShareStop() {
        try {
            this.init();
            if (!this.ctx) return;
            const now = this.ctx.currentTime;
            const notes = [
                { freq: 987.77, time: 0, duration: 0.14 },    // B5
                { freq: 783.99, time: 0.08, duration: 0.16 },  // G5
                { freq: 587.33, time: 0.16, duration: 0.25 }   // D5
            ];

            notes.forEach(n => {
                const osc = this.ctx.createOscillator();
                const gain = this.ctx.createGain();

                osc.type = 'sine';
                osc.frequency.setValueAtTime(n.freq, now + n.time);

                gain.gain.setValueAtTime(0.0001, now + n.time);
                gain.gain.linearRampToValueAtTime(0.15, now + n.time + 0.02);
                gain.gain.exponentialRampToValueAtTime(0.0001, now + n.time + n.duration);

                osc.connect(gain);
                gain.connect(this.ctx.destination);

                osc.start(now + n.time);
                osc.stop(now + n.time + n.duration);
            });
        } catch (e) {
            console.debug("Erro no sintetizador de áudio:", e);
        }
    },
    // Alerta sonoro de inatividade (Sino duplo suave de atenção)
    playInactivityWarning() {
        try {
            this.init();
            if (!this.ctx) return;
            const now = this.ctx.currentTime;
            const tones = [
                { freq: 880, time: 0, duration: 0.25 },     // A5
                { freq: 1174.66, time: 0.18, duration: 0.4 } // D6
            ];

            tones.forEach(t => {
                const osc = this.ctx.createOscillator();
                const gain = this.ctx.createGain();

                osc.type = 'triangle';
                osc.frequency.setValueAtTime(t.freq, now + t.time);

                gain.gain.setValueAtTime(0.0001, now + t.time);
                gain.gain.linearRampToValueAtTime(0.22, now + t.time + 0.02);
                gain.gain.exponentialRampToValueAtTime(0.0001, now + t.time + t.duration);

                osc.connect(gain);
                gain.connect(this.ctx.destination);

                osc.start(now + t.time);
                osc.stop(now + t.time + t.duration);
            });
        } catch (e) {
            console.debug("Erro no som de inatividade:", e);
        }
    }
};

// Helpers de Qualidade e Ícones Dinâmicos
function getQualityFriendlyName(key) {
    switch (key) {
        case '1080p60': return '1080p @ 60 FPS';
        case '1080p30': return '1080p @ 30 FPS';
        case '720p60':  return '720p @ 60 FPS';
        default: return key || '1080p @ 60 FPS';
    }
}

function updateQualityIndicator(qualityLabel, isActive = false) {
    if (!dom.qualityText || !dom.qualityDot) return;
    if (isActive) {
        dom.qualityDot.className = 'pill-dot green';
        dom.qualityText.textContent = qualityLabel || '1080p @ 60 FPS';
    } else {
        dom.qualityDot.className = 'pill-dot gray';
        const sel = dom.qualitySelect ? dom.qualitySelect.value : '1080p60';
        dom.qualityText.textContent = `Pronto (${getQualityFriendlyName(sel).replace(' @ ', ' ')})`;
    }
}

function getVolumeSvg(vol, isMuted) {
    if (isMuted || vol === 0) {
        return `<svg class="svg-icon svg-icon-sm" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"/>
            <line x1="22" x2="16" y1="9" y2="15"/>
            <line x1="16" x2="22" y1="9" y2="15"/>
        </svg>`;
    } else if (vol < 0.5) {
        return `<svg class="svg-icon svg-icon-sm" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"/>
            <path d="M15.54 8.46a5 5 0 0 1 0 7.07"/>
        </svg>`;
    } else {
        return `<svg class="svg-icon svg-icon-sm" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"/>
            <path d="M15.54 8.46a5 5 0 0 1 0 7.07"/>
            <path d="M19.07 4.93a10 10 0 0 1 0 14.14"/>
        </svg>`;
    }
}

// Requisições HTTP com Cabeçalho de Proteção por Token de Sessão
function fetchWithAuth(url, options = {}) {
    const headers = { ...(options.headers || {}) };
    if (state.sessionToken) {
        headers['X-Session-Token'] = state.sessionToken;
    }
    return fetch(url, { ...options, headers });
}

function showJoinError(msg) {
    if (dom.joinErrorMsg) {
        dom.joinErrorMsg.textContent = msg;
        dom.joinErrorMsg.classList.remove('hidden');
    }
}

function clearJoinError() {
    if (dom.joinErrorMsg) {
        dom.joinErrorMsg.textContent = '';
        dom.joinErrorMsg.classList.add('hidden');
    }
}

// Limpa qualquer senha em texto puro residual do navegador (migração de segurança)
sessionStorage.removeItem('streamflow_pwd');

// Botões de fechar alertas
if (dom.closeWarningBannerBtn && dom.quotaWarningBanner) {
    dom.closeWarningBannerBtn.addEventListener('click', () => {
        dom.quotaWarningBanner.classList.add('hidden');
    });
}

if (dom.btnCutoffOk && dom.cutoffModal) {
    dom.btnCutoffOk.addEventListener('click', () => {
        dom.cutoffModal.classList.add('hidden');
    });
}

// ==========================================
// 0.1 Gerenciamento de Inatividade (Anti-Dormir)
// ==========================================

const inactivityManager = {
    // 2 horas (120 minutos) de inatividade para disparar aviso
    INACTIVITY_TIMEOUT_MS: 2 * 60 * 60 * 1000,
    // 60 segundos de tolerância na contagem regressiva
    COUNTDOWN_SECONDS: 60,

    inactivityTimer: null,
    countdownTimer: null,
    secondsLeft: 60,
    isModalOpen: false,

    initListeners() {
        const resetActivity = () => {
            if (!state.isSharing) return;
            // Se o modal de aviso já estiver aberto, a atividade física não fecha sozinha: 
            // exige clique explícito no botão para confirmar que está acordado.
            if (!this.isModalOpen) {
                this.restartInactivityTimer();
            }
        };

        window.addEventListener('mousemove', resetActivity, { passive: true });
        window.addEventListener('mousedown', resetActivity, { passive: true });
        window.addEventListener('keydown', resetActivity, { passive: true });
        window.addEventListener('touchstart', resetActivity, { passive: true });
        window.addEventListener('scroll', resetActivity, { passive: true });
    },

    start() {
        this.stop();
        this.restartInactivityTimer();
    },

    stop() {
        if (this.inactivityTimer) {
            clearTimeout(this.inactivityTimer);
            this.inactivityTimer = null;
        }
        if (this.countdownTimer) {
            clearInterval(this.countdownTimer);
            this.countdownTimer = null;
        }
        this.isModalOpen = false;
        if (dom.inactivityModal) {
            dom.inactivityModal.classList.add('hidden');
        }
    },

    restartInactivityTimer() {
        if (this.inactivityTimer) {
            clearTimeout(this.inactivityTimer);
        }
        this.inactivityTimer = setTimeout(() => {
            if (state.isSharing) {
                this.triggerWarning();
            }
        }, this.INACTIVITY_TIMEOUT_MS);
    },

    triggerWarning() {
        this.isModalOpen = true;
        this.secondsLeft = this.COUNTDOWN_SECONDS;

        if (dom.inactivityCountdownNum) {
            dom.inactivityCountdownNum.textContent = this.secondsLeft;
        }
        if (dom.inactivityModal) {
            dom.inactivityModal.classList.remove('hidden');
        }

        // Toca alerta sonoro agradável para chamar atenção de quem estiver distante
        soundManager.playInactivityWarning();

        if (this.countdownTimer) {
            clearInterval(this.countdownTimer);
        }

        this.countdownTimer = setInterval(() => {
            this.secondsLeft--;
            if (dom.inactivityCountdownNum) {
                dom.inactivityCountdownNum.textContent = this.secondsLeft;
            }

            // A cada 20 segundos toca um lembrete se ainda não respondeu
            if (this.secondsLeft % 20 === 0 && this.secondsLeft > 0) {
                soundManager.playInactivityWarning();
            }

            if (this.secondsLeft <= 0) {
                clearInterval(this.countdownTimer);
                this.countdownTimer = null;
                this.executeAutoShutdown();
            }
        }, 1000);
    },

    stayActive() {
        this.stop();
        if (state.isSharing) {
            this.start();
        }
    },

    executeAutoShutdown() {
        this.stop();
        if (state.isSharing) {
            appendSystemMessage("⚠️ Transmissão encerrada automaticamente por ausência de resposta ao alerta de inatividade.");
            stopScreenShare();
        }
    }
};

// Inicializa captura de eventos do teclado/mouse
inactivityManager.initListeners();

if (dom.btnInactivityStay) {
    dom.btnInactivityStay.addEventListener('click', () => {
        inactivityManager.stayActive();
    });
}

if (dom.btnInactivityStop) {
    dom.btnInactivityStop.addEventListener('click', () => {
        inactivityManager.executeAutoShutdown();
    });
}

// ==========================================
// 1. Inicialização e Entrada na Sala
// ==========================================

// ------------------------------------------
// Login com Discord
// ------------------------------------------

// localStorage pode estar bloqueado (modo privado, política do navegador): nesse caso o login vale só na aba
function readStored(key) {
    try {
        return localStorage.getItem(key) || '';
    } catch (e) {
        return '';
    }
}

function loadStoredDiscordUser() {
    try {
        const raw = readStored('chimpacast_discord');
        const user = raw ? JSON.parse(raw) : null;
        return user && user.name ? user : null;
    } catch (e) {
        return null;
    }
}

function clearDiscordSession() {
    state.discordUser = null;
    localStorage.removeItem('chimpacast_discord');
    renderDiscordLoginState();
}

function renderDiscordLoginState() {
    const connected = !!(state.discordUser && state.sessionToken);
    const chip = document.getElementById('discord-user-chip');
    if (!chip) return;

    if (connected) {
        document.getElementById('discord-name').textContent = state.discordUser.name;
        const url = state.discordUser.avatarUrl || '';
        document.getElementById('discord-avatar').src = url.startsWith('https://cdn.discordapp.com/') ? url : '';
        dom.usernameInput.value = state.discordUser.name;
    }

    chip.classList.toggle('hidden', !connected);
    document.getElementById('discord-login-btn').classList.toggle('hidden', connected);
    document.getElementById('join-room-group').classList.toggle('hidden', !connected);
    document.getElementById('join-submit-btn').classList.toggle('hidden', !connected);
}

async function initDiscordLogin() {
    let config = { discordLogin: false };
    try {
        const res = await fetch('/api/auth/config');
        if (res.ok) config = await res.json();
    } catch (e) {
        console.warn('Não foi possível carregar a configuração de login:', e);
    }

    if (!config.discordLogin) {
        document.getElementById('discord-login-btn').classList.add('hidden');
        showJoinError('O login com Discord não está configurado no servidor. Avise o administrador.');
    }

    const params = new URLSearchParams(window.location.search);
    const ticket = params.get('discord_ticket');
    const discordError = params.get('discord_error');
    if (ticket || discordError) {
        window.history.replaceState({}, '', window.location.pathname);
    }

    if (ticket) {
        try {
            const res = await fetch('/api/auth/discord/exchange', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ ticket })
            });
            const data = await res.json().catch(() => ({}));
            if (res.ok && data.valid) {
                state.sessionToken = data.token;
                state.discordUser = data.user;
                state.authFailed = false;
                localStorage.setItem('chimpacast_token', data.token);
                localStorage.setItem('chimpacast_discord', JSON.stringify(data.user));
            } else {
                showJoinError(data.message || 'Login com Discord expirado. Tente novamente.');
            }
        } catch (e) {
            showJoinError('Não foi possível concluir o login com o Discord.');
        }
    } else if (discordError) {
        const messages = {
            not_member: 'Você precisa ser membro do servidor do Discord para entrar.',
            missing_role: 'Seu usuário não tem o cargo necessário para entrar.',
            rate_limited: 'Muitas tentativas de login! Aguarde 1 minuto.',
            invalid_request: 'Login com Discord cancelado ou expirado. Tente novamente.'
        };
        showJoinError(messages[discordError] || 'Não foi possível entrar com o Discord.');
    }

    renderDiscordLoginState();

    if (!ticket && state.sessionToken && state.discordUser) {
        await validateStoredSession();
    }
}

// Confirma com o servidor que a sessão salva ainda vale (não expirou, segue membro do servidor)
async function validateStoredSession() {
    try {
        const res = await fetchWithAuth('/api/auth/me');
        if (res.ok) {
            const data = await res.json();
            if (data.valid && data.user) {
                state.discordUser = data.user;
                localStorage.setItem('chimpacast_discord', JSON.stringify(data.user));
            }
        } else if (res.status === 401 || res.status === 403) {
            const data = await res.json().catch(() => ({}));
            state.sessionToken = '';
            localStorage.removeItem('chimpacast_token');
            clearDiscordSession();
            showJoinError(data.message || 'Sua sessão expirou. Entre com o Discord novamente.');
        }
    } catch (e) {
        console.warn('Não foi possível validar a sessão salva:', e);
    }
    renderDiscordLoginState();
}

const discordLogoutBtn = document.getElementById('discord-logout-btn');
if (discordLogoutBtn) {
    discordLogoutBtn.addEventListener('click', () => {
        if (state.sessionToken) {
            fetchWithAuth('/api/auth/logout', { method: 'POST' }).catch(() => {});
        }
        clearDiscordSession();
        state.sessionToken = '';
        localStorage.removeItem('chimpacast_token');
        dom.usernameInput.value = '';
    });
}

initDiscordLogin();

dom.joinForm.addEventListener('submit', (e) => {
    e.preventDefault();
    clearJoinError();

    if (!(state.discordUser && state.sessionToken)) {
        showJoinError('Entre com o Discord para continuar.');
        return;
    }

    const username = state.discordUser.name;
    const room = dom.roomInput.value.trim().toLowerCase();
    if (!room) return;

    soundManager.init();

    state.username = username;
    state.room = room;
    state.authFailed = false;

    dom.displayUsername.textContent = username;
    dom.displayRoomName.textContent = room;

    dom.modal.classList.add('hidden');
    dom.app.classList.remove('hidden');

    connectWebSocket();
    fetchQuota();
});

let reconnectAttempts = 0;
let wsConnectedSuccessfully = false;

function handleSessionExpired(msg) {
    state.authFailed = true;
    clearDiscordSession();
    state.sessionToken = '';
    localStorage.removeItem('chimpacast_token');
    sessionStorage.removeItem('streamflow_token');
    sessionStorage.removeItem('streamflow_pwd');
    if (state.wsPingInterval) {
        clearInterval(state.wsPingInterval);
        state.wsPingInterval = null;
    }
    if (state.ws) {
        try { state.ws.close(); } catch(e) {}
    }
    dom.modal.classList.remove('hidden');
    dom.app.classList.add('hidden');
    showJoinError(msg || "Sessão expirada. Faça login novamente.");
}

function connectWebSocket() {
    if (!state.sessionToken) {
        console.warn("Nenhuma credencial disponível para WebSocket. Redirecionando para login.");
        handleSessionExpired("Por favor, faça login para entrar na sala.");
        return;
    }

    if (state.wsPingInterval) {
        clearInterval(state.wsPingInterval);
        state.wsPingInterval = null;
    }

    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const authQuery = `?token=${encodeURIComponent(state.sessionToken)}`;
    const wsUrl = `${protocol}//${window.location.host}/ws/rooms${authQuery}`;

    dom.statusText.textContent = "Conectando...";
    wsConnectedSuccessfully = false;
    state.ws = new WebSocket(wsUrl);

    state.ws.onopen = () => {
        reconnectAttempts = 0;
        wsConnectedSuccessfully = true;
        dom.statusText.textContent = "Conectado";
        sendWs({
            type: 'JOIN_ROOM',
            room: state.room,
            username: state.username,
            userId: state.userId,
            token: state.sessionToken
        });

        // Se estava transmitindo tela durante uma micro-queda do WebSocket, re-anuncia a tela ativa
        if (state.isSharing && state.publisherSessionId && state.publishedTracks) {
            setTimeout(() => {
                if (state.isSharing && state.publisherSessionId && state.publishedTracks) {
                    sendWs({
                        type: 'SCREEN_STARTED',
                        sessionId: state.publisherSessionId,
                        quality: state.publishedQuality || '1080p @ 60 FPS',
                        tracks: state.publishedTracks
                    });
                }
            }, 250);
        }

        // Heartbeat a cada 25s para evitar timeout de conexão no Cloudflare/Proxy
        state.wsPingInterval = setInterval(() => {
            sendWs({ type: 'PING' });
        }, 25000);
    };

    state.ws.onmessage = async (event) => {
        try {
            const data = JSON.parse(event.data);
            handleWsMessage(data);
        } catch (err) {
            console.error("Erro ao analisar mensagem WebSocket:", err);
        }
    };

    state.ws.onclose = async (event) => {
        if (state.wsPingInterval) {
            clearInterval(state.wsPingInterval);
            state.wsPingInterval = null;
        }
        dom.statusText.textContent = "Desconectado";

        // Se a sessão foi substituída por outra aba/conexão do mesmo usuário
        if (event.code === 4002) {
            console.warn("Sessão substituída por uma nova conexão.");
            return;
        }
        
        // Se a conexão foi rejeitada por falha explícita de autenticação
        if (event.code === 4001 || event.code === 1003 || event.code === 1008 || state.authFailed) {
            handleSessionExpired("Acesso rejeitado: sessão expirada ou inválida! Por favor, entre novamente.");
            return;
        }

        // Se falhou antes de conectar (handshake 401 vira 1006 no navegador), checa via HTTP se a sessão ainda existe
        if (!wsConnectedSuccessfully || event.code === 1006) {
            try {
                const checkRes = await fetchWithAuth('/api/quota');
                if (checkRes.status === 401) {
                    console.warn("Sessão invalidada no backend. Interrompendo reconexões WebSocket.");
                    handleSessionExpired("Sua sessão expirou ou o servidor foi reiniciado. Por favor, faça login novamente.");
                    return;
                }
            } catch (netErr) {
                // Erro de rede genérico, continua com a rotina de reconexão
            }
        }

        reconnectAttempts++;
        if (reconnectAttempts > 4) {
            handleSessionExpired("Não foi possível restabelecer conexão com o servidor. Por favor, entre novamente.");
            return;
        }

        // Reconecta apenas se o usuário estiver autenticado e na tela da sala
        if (dom.modal.classList.contains('hidden') && state.sessionToken && !state.authFailed) {
            console.warn(`WebSocket desconectado. Tentativa de reconexão ${reconnectAttempts}/4 em 3s...`);
            setTimeout(() => {
                if (dom.modal.classList.contains('hidden') && state.sessionToken && !state.authFailed) {
                    connectWebSocket();
                }
            }, 3000);
        }
    };

    state.ws.onerror = (err) => {
        console.error("Erro de conexão WebSocket:", err);
    };
}

function sendWs(payload) {
    if (state.ws && state.ws.readyState === WebSocket.OPEN) {
        state.ws.send(JSON.stringify(payload));
    }
}

// ==========================================
// 2. Gerenciamento de Mensagens WebSocket
// ==========================================

async function handleWsMessage(msg) {
    switch (msg.type) {
        case 'PONG':
            break;

        case 'ROOM_STATE':
            if (msg.allowRemoteScreenKick !== undefined) {
                state.allowRemoteScreenKick = Boolean(msg.allowRemoteScreenKick);
            }
            updateParticipants(msg.participants);
            if (msg.screens && Array.isArray(msg.screens)) {
                const activeMap = new Map();
                for (const s of msg.screens) {
                    if (s.userId !== state.userId) {
                        activeMap.set(s.userId, s);
                    }
                }

                // Remove inscrições locais que não existem mais no servidor ou mudaram de sessionId
                for (const [subUid, sub] of Array.from(state.subscriptions.entries())) {
                    const serverScreen = activeMap.get(subUid);
                    if (!serverScreen || serverScreen.sessionId !== sub.publisherSessionId) {
                        removeScreen(subUid);
                    }
                }

                // Remove cards órfãos no DOM que não correspondam a telas ativas
                dom.screensGrid.querySelectorAll('.screen-card').forEach(card => {
                    const cardUid = card.dataset.userId || card.id.replace(/^card-/, '');
                    if (cardUid !== state.userId && !activeMap.has(cardUid)) {
                        removeScreen(cardUid);
                    }
                });

                for (const screen of msg.screens) {
                    if (screen.userId !== state.userId) {
                        await subscribeToScreen(screen);
                    }
                }
                if (msg.screens.length > 0) {
                    updateQualityIndicator(msg.screens[0].quality || '1080p @ 60 FPS', true);
                } else if (!state.isSharing) {
                    updateQualityIndicator(null, false);
                }
            } else {
                // Nenhuma tela ativa no servidor: limpa quaisquer telas remotas residuais
                for (const [subUid] of Array.from(state.subscriptions.entries())) {
                    removeScreen(subUid);
                }
                if (!state.isSharing) {
                    updateQualityIndicator(null, false);
                }
            }
            updateGridLayout();
            break;

        case 'SETTINGS_UPDATED':
            if (msg.allowRemoteScreenKick !== undefined) {
                state.allowRemoteScreenKick = Boolean(msg.allowRemoteScreenKick);
                // Atualiza visibilidade dos botões de kick em telas ativas
                document.querySelectorAll('.kick-screen-btn').forEach(btn => {
                    btn.style.display = state.allowRemoteScreenKick ? 'flex' : 'none';
                });
            }
            break;

        case 'FORCE_STOP_SCREEN':
            if (state.isSharing) {
                stopScreenShare();
                inactivityManager.stop();
                alert(`⚠️ Sua transmissão foi encerrada: ${msg.reason || 'Ação solicitada por outro usuário.'}`);
            }
            break;

        case 'KICK_SCREEN_REJECTED':
            alert(`Aviso: ${msg.reason || 'Operação não permitida.'}`);
            break;

        case 'USER_JOINED':
            appendSystemMessage(`${msg.username} entrou na sala.`);
            if (Array.isArray(msg.participants)) {
                updateParticipants(msg.participants);
            } else if (msg.userId && msg.username) {
                const exists = state.participants.some(p => p.userId === msg.userId);
                if (!exists) {
                    updateParticipants([...state.participants, { userId: msg.userId, username: msg.username }]);
                }
            }
            break;

        case 'USER_LEFT':
            appendSystemMessage(`${msg.username} saiu da sala.`);
            removeScreen(msg.userId, msg.username);
            if (Array.isArray(msg.participants)) {
                updateParticipants(msg.participants);
            } else if (msg.userId) {
                updateParticipants(state.participants.filter(p => p.userId !== msg.userId));
            }
            if (state.subscriptions.size === 0 && !state.isSharing) {
                updateQualityIndicator(null, false);
            }
            break;

        case 'SCREEN_STARTED':
            soundManager.playScreenShareStart();
            updateQualityIndicator(msg.quality || '1080p @ 60 FPS', true);
            if (msg.userId !== state.userId) {
                appendSystemMessage(`${msg.username} começou a transmitir a tela (${msg.quality || '1080p @ 60 FPS'}).`);
                await subscribeToScreen(msg);
            }
            renderParticipantsModal();
            updateGridLayout();
            break;

        case 'SCREEN_STOPPED':
            soundManager.playScreenShareStop();
            appendSystemMessage(`Transmissão de tela finalizada.`);
            removeScreen(msg.userId, msg.username);
            if (state.subscriptions.size === 0 && !state.isSharing) {
                updateQualityIndicator(null, false);
            }
            renderParticipantsModal();
            updateGridLayout();
            break;

        case 'CHAT_MESSAGE':
            appendChatMessage(msg);
            break;

        case 'QUOTA_UPDATE':
            updateQuotaDisplay(msg);
            if (msg.warningNearLimit || (msg.percentageRemaining !== undefined && msg.percentageRemaining <= 10)) {
                if (dom.quotaWarningBanner) {
                    dom.quotaWarningBanner.classList.remove('hidden');
                }
            }
            break;

        case 'EMERGENCY_QUOTA_CUTOFF':
            state.quotaAvailable = false;
            stopScreenShare();
            for (const [uid] of Array.from(state.subscriptions.entries())) {
                removeScreen(uid);
            }
            if (dom.cutoffReasonText && msg.reason) {
                dom.cutoffReasonText.textContent = msg.reason;
            }
            if (dom.cutoffModal) {
                dom.cutoffModal.classList.remove('hidden');
            }
            appendSystemMessage(`🛑 Kill-Switch de Cota ativado: transmissões encerradas compulsoriamente para garantir custo zero!`);
            break;

        case 'AUTH_ERROR':
            state.authFailed = true;
            clearDiscordSession();
            state.sessionToken = '';
            localStorage.removeItem('chimpacast_token');
            sessionStorage.removeItem('streamflow_token');
            sessionStorage.removeItem('streamflow_pwd');
            if (state.ws) {
                try { state.ws.close(4001, "Auth Error"); } catch(e) {}
            }
            dom.modal.classList.remove('hidden');
            dom.app.classList.add('hidden');
            showJoinError(msg.message || "Acesso negado: credencial de acesso inválida ou expirada!");
            break;
    }
}

// ==========================================
// 3. Compartilhamento de Tela (Transmissor)
// ==========================================

async function toggleScreenShare() {
    if (state.isSharing) {
        stopScreenShare();
    } else {
        await startScreenShare();
    }
}

dom.shareBtn.addEventListener('click', toggleScreenShare);
dom.emptyShareBtn.addEventListener('click', toggleScreenShare);

async function startScreenShare() {
    if (!state.quotaAvailable) {
        alert("⚠️ Transmissão Bloqueada: A cota gratuita deste mês atingiu o limite de segurança de 990 GB! Para garantir custo zero, nenhuma transmissão pode ser iniciada até a renovação no dia 1º do próximo mês.");
        return;
    }

    try {
        const qualityConfig = getQualityConstraints();

        // 1. Captura de tela pelo navegador com áudio do sistema
        state.localStream = await navigator.mediaDevices.getDisplayMedia({
            video: {
                ...qualityConfig.video,
                cursor: "always"
            },
            audio: {
                autoGainControl: false,
                echoCancellation: false,
                noiseSuppression: false,
                channelCount: 2
            }
        });

        const [videoTrack] = state.localStream.getVideoTracks();
        if (videoTrack) {
            // Não usar "motion" aqui pois "motion" força o WebRTC a derrubar a resolução de 1080p para 540p/720p (pixelado).
            videoTrack.onended = () => {
                stopScreenShare();
            };
        }

        // 2. Cria sessão na Cloudflare Calls via backend Java protegido com senha e cota
        const sessionRes = await fetchWithAuth('/api/calls/sessions/new', { method: 'POST' });
        if (!sessionRes.ok) {
            const errData = await sessionRes.json().catch(() => ({ error: `Erro HTTP ${sessionRes.status}` }));
            if (sessionRes.status === 403) {
                state.quotaAvailable = false;
                alert("⚠️ Transmissão Bloqueada:\n" + (errData.error || "Limite de cota gratuita atingido!"));
            } else {
                alert("Não foi possível conectar com o serviço Cloudflare Calls: " + (errData.error || sessionRes.statusText));
            }
            stopScreenShare();
            return;
        }

        const sessionData = await sessionRes.json();
        state.publisherSessionId = sessionData.sessionId;

        // 3. Cria PeerConnection local
        state.publisherPc = new RTCPeerConnection({
            iceServers: [{ urls: 'stun:stun.cloudflare.com:3478' }]
        });

        // 4. Adiciona tracks no PeerConnection com parâmetros de alta taxa de bits e resolução travada em 1:1
        const transceivers = [];
        state.localStream.getTracks().forEach((track) => {
            const isVideo = track.kind === 'video';
            const transceiverInit = isVideo ? {
                direction: 'sendonly',
                sendEncodings: [{
                    maxBitrate: qualityConfig.maxBitrate,
                    maxFramerate: qualityConfig.fps,
                    scaleResolutionDownBy: 1.0,
                    priority: 'high',
                    networkPriority: 'high'
                }]
            } : {
                direction: 'sendonly',
                sendEncodings: [{
                    maxBitrate: 256000,
                    priority: 'high',
                    networkPriority: 'high'
                }]
            };
            const transceiver = state.publisherPc.addTransceiver(track, transceiverInit);
            if (isVideo) {
                preferHighQualityVideoCodecs(transceiver);
            }
            transceivers.push({ transceiver, track });
        });

        // Aplica preferência de manter resolução Full HD nativa (proíbe downscaling automático)
        await applyHighQualitySenderParameters(state.publisherPc, qualityConfig);

        // 5. Gera Oferta SDP local
        const offer = await state.publisherPc.createOffer();
        await state.publisherPc.setLocalDescription(offer);

        // Aguarda candidatos ICE locais serem gerados
        await waitForIceGathering(state.publisherPc);

        // Crucial: O mid só é preenchido pelo navegador APÓS o setLocalDescription!
        const tracksToRegister = transceivers.map(({ transceiver, track }) => ({
            location: "local",
            mid: transceiver.mid,
            trackName: track.kind === 'video' ? 'screen-video' : 'screen-audio'
        }));

        console.log("Registrando tracks na Cloudflare:", tracksToRegister);

        // 6. Envia tracks e SDP para a Cloudflare Calls
        const tracksRes = await fetchWithAuth(`/api/calls/sessions/${state.publisherSessionId}/tracks/new`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                sessionDescription: {
                    type: "offer",
                    sdp: state.publisherPc.localDescription.sdp
                },
                tracks: tracksToRegister
            })
        });

        if (!tracksRes.ok) {
            const errData = await tracksRes.json().catch(() => ({ error: 'Falha desconhecida na API' }));
            throw new Error(errData.error || `Erro HTTP ${tracksRes.status}`);
        }

        const tracksData = await tracksRes.json();

        // 7. Define a resposta (Answer) da Cloudflare com boost de bitrate inicial e máximo
        if (tracksData.sessionDescription) {
            try {
                const boostedSdp = boostSdpBitrate(tracksData.sessionDescription.sdp, qualityConfig);
                await state.publisherPc.setRemoteDescription(new RTCSessionDescription({
                    type: tracksData.sessionDescription.type,
                    sdp: boostedSdp
                }));
            } catch (sdpErr) {
                console.warn("Fallback para SDP padrão da Cloudflare:", sdpErr);
                await state.publisherPc.setRemoteDescription(new RTCSessionDescription(tracksData.sessionDescription));
            }
        }

        // Reforça os parâmetros de resolução 1:1 e bitrate máximo após negociação SDP
        await applyHighQualitySenderParameters(state.publisherPc, qualityConfig);

        const currentQualityChoice = dom.qualitySelect ? dom.qualitySelect.value : '1080p60';
        const currentQualityLabel = getQualityFriendlyName(currentQualityChoice);

        state.isSharing = true;
        state.publishedTracks = tracksToRegister;
        state.publishedQuality = currentQualityLabel;

        dom.shareBtn.classList.add('active');
        dom.shareBtnText.textContent = "Parar de Compartilhar";
        updateQualityIndicator(currentQualityLabel, true);

        // Cria o card de visualização local da própria tela (mutado)
        renderScreenCard(state.userId, `${state.username} (Você)`, state.localStream, true);

        // 8. Avisa a sala via WebSocket com a qualidade escolhida
        sendWs({
            type: 'SCREEN_STARTED',
            sessionId: state.publisherSessionId,
            quality: currentQualityLabel,
            tracks: tracksToRegister
        });

        // Inicia o monitoramento de inatividade (anti-esquecer / anti-dormir na live)
        inactivityManager.start();

        updateGridLayout();

    } catch (err) {
        console.error("Erro ao iniciar compartilhamento de tela:", err);
        if (err.name !== 'NotAllowedError') {
            alert("Não foi possível compartilhar a tela: " + err.message);
        }
        stopScreenShare();
    }
}

function stopScreenShare() {
    // Para o monitoramento de inatividade e fecha modal se aberto
    inactivityManager.stop();

    if (state.localStream) {
        state.localStream.getTracks().forEach(t => t.stop());
        state.localStream = null;
    }

    if (state.publisherPc) {
        try { state.publisherPc.close(); } catch (e) {}
        state.publisherPc = null;
    }

    state.isSharing = false;
    state.publisherSessionId = null;
    state.publishedTracks = null;
    state.publishedQuality = null;

    dom.shareBtn.classList.remove('active');
    dom.shareBtnText.textContent = "Compartilhar Tela";

    removeScreen(state.userId);

    if (state.subscriptions.size === 0) {
        updateQualityIndicator(null, false);
    }

    sendWs({ type: 'SCREEN_STOPPED' });
    updateGridLayout();
}

if (dom.qualitySelect) {
    dom.qualitySelect.addEventListener('change', () => {
        if (!state.isSharing && state.subscriptions.size === 0) {
            updateQualityIndicator(null, false);
        }
    });
}

// ==========================================
// 4. Inscrição em Telas Remotas (Espectador)
// ==========================================

async function subscribeToScreen(screen) {
    const remoteUserId = screen.userId;
    const remoteUsername = (screen.username || '').trim();

    // 1. Verifica se já existe inscrição para este mesmo userId
    const existingSub = state.subscriptions.get(remoteUserId);
    if (existingSub) {
        const isSameSession = existingSub.publisherSessionId === screen.sessionId;
        const pcAlive = existingSub.pc && !['failed', 'closed'].includes(existingSub.pc.connectionState);
        if (isSameSession && !existingSub.cancelled && (existingSub.connecting || pcAlive)) {
            return;
        }
        // Se for uma nova sessão do mesmo userId ou se a anterior falhou, limpa antes de recriar
        removeScreen(remoteUserId);
    }

    // 2. Garante que não existam lives fantasmas de outro userId antigo do mesmo username
    if (remoteUsername) {
        for (const [uid, sub] of Array.from(state.subscriptions.entries())) {
            if (uid !== remoteUserId && sub.username && sub.username.toLowerCase() === remoteUsername.toLowerCase()) {
                removeScreen(uid, sub.username);
            }
        }
        dom.screensGrid.querySelectorAll('.screen-card').forEach(card => {
            const cardUid = card.dataset.userId || card.id.replace(/^card-/, '');
            const cardUname = (card.dataset.username || '').trim();
            if (cardUid !== state.userId && cardUid !== remoteUserId &&
                cardUname.toLowerCase() === remoteUsername.toLowerCase()) {
                removeScreen(cardUid, cardUname);
            }
        });
    }

    // 3. Registra imediatamente a entrada (lock assíncrono) para evitar condições de corrida
    const subEntry = {
        pc: null,
        sessionId: null,
        publisherSessionId: screen.sessionId,
        username: remoteUsername,
        connecting: true,
        cancelled: false,
        disconnectTimer: null
    };
    state.subscriptions.set(remoteUserId, subEntry);

    let subscriberPc = null;

    try {
        // 1. Cria uma sessão na Cloudflare Calls para o espectador
        const sessionRes = await fetchWithAuth('/api/calls/sessions/new', { method: 'POST' });
        if (!sessionRes.ok) {
            const errData = await sessionRes.json().catch(() => ({ error: 'Falha ao iniciar sessão de espectador' }));
            throw new Error(errData.error || `Erro HTTP ${sessionRes.status}`);
        }
        const sessionData = await sessionRes.json();
        const subscriberSessionId = sessionData.sessionId;

        if (subEntry.cancelled || state.subscriptions.get(remoteUserId) !== subEntry) {
            return;
        }

        // 2. Cria PeerConnection para receber a mídia
        subscriberPc = new RTCPeerConnection({
            iceServers: [{ urls: 'stun:stun.cloudflare.com:3478' }]
        });
        subEntry.pc = subscriberPc;
        subEntry.sessionId = subscriberSessionId;

        const remoteStream = new MediaStream();

        subscriberPc.ontrack = (event) => {
            remoteStream.addTrack(event.track);
        };

        // Monitora quedas definitivas da conexão WebRTC do transmissor
        subscriberPc.onconnectionstatechange = () => {
            if (subEntry.cancelled || state.subscriptions.get(remoteUserId) !== subEntry) return;
            const st = subscriberPc.connectionState;
            if (st === 'connected') {
                if (subEntry.disconnectTimer) {
                    clearTimeout(subEntry.disconnectTimer);
                    subEntry.disconnectTimer = null;
                }
            } else if (st === 'failed' || st === 'closed') {
                console.warn(`Conexão WebRTC de ${remoteUsername} (${remoteUserId}) encerrada (${st}). Removendo card.`);
                removeScreen(remoteUserId, remoteUsername);
            } else if (st === 'disconnected') {
                if (!subEntry.disconnectTimer) {
                    subEntry.disconnectTimer = setTimeout(() => {
                        if (!subEntry.cancelled &&
                            state.subscriptions.get(remoteUserId) === subEntry &&
                            ['disconnected', 'failed', 'closed'].includes(subscriberPc.connectionState)) {
                            console.warn(`Conexão WebRTC de ${remoteUsername} permaneceu desconectada. Removendo card travado.`);
                            removeScreen(remoteUserId, remoteUsername);
                        }
                    }, 10000);
                }
            }
        };

        // 3. Monta lista de tracks a assinar
        const tracksToSubscribe = screen.tracks.map(t => ({
            location: "remote",
            sessionId: screen.sessionId,
            trackName: t.trackName
        }));

        // 4. Solicita a inscrição dos tracks à Cloudflare Calls
        const subTracksRes = await fetchWithAuth(`/api/calls/sessions/${subscriberSessionId}/tracks/new`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ tracks: tracksToSubscribe })
        });

        if (!subTracksRes.ok) {
            const errData = await subTracksRes.json().catch(() => ({ error: 'Falha ao assinar tracks' }));
            throw new Error(errData.error || `Erro HTTP ${subTracksRes.status}`);
        }

        const subTracksData = await subTracksRes.json();

        if (subEntry.cancelled || state.subscriptions.get(remoteUserId) !== subEntry) {
            try { subscriberPc.close(); } catch (e) {}
            return;
        }

        // 5. Cloudflare devolve uma Oferta SDP; aplicamos e geramos uma Resposta (Answer)
        if (subTracksData.sessionDescription) {
            await subscriberPc.setRemoteDescription(new RTCSessionDescription(subTracksData.sessionDescription));
            const answer = await subscriberPc.createAnswer();
            await subscriberPc.setLocalDescription(answer);

            // Aguarda candidatos ICE
            await waitForIceGathering(subscriberPc);

            if (subEntry.cancelled || state.subscriptions.get(remoteUserId) !== subEntry) {
                try { subscriberPc.close(); } catch (e) {}
                return;
            }

            // 6. Envia a resposta de volta para a Cloudflare para fechar o handshake
            await fetchWithAuth(`/api/calls/sessions/${subscriberSessionId}/renegotiate`, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    sessionDescription: {
                        type: "answer",
                        sdp: subscriberPc.localDescription.sdp
                    }
                })
            });
        }

        if (subEntry.cancelled || state.subscriptions.get(remoteUserId) !== subEntry) {
            try { subscriberPc.close(); } catch (e) {}
            return;
        }

        subEntry.connecting = false;

        // Renderiza o card do vídeo remoto na grade
        renderScreenCard(remoteUserId, screen.username, remoteStream, false);
        updateGridLayout();

    } catch (err) {
        console.error(`Erro ao assinar tela de ${screen.username}:`, err);
        if (subscriberPc) {
            try { subscriberPc.close(); } catch (e) {}
        }
        if (state.subscriptions.get(remoteUserId) === subEntry) {
            state.subscriptions.delete(remoteUserId);
        }
        updateGridLayout();
    }
}

// ==========================================
// 5. Renderização da Interface e Cards de Vídeo
// ==========================================

function renderScreenCard(userId, username, stream, isMuted = false) {
    // Antes de renderizar um card remoto, remove qualquer card antigo que tenha o mesmo username mas outro userId
    if (!isMuted && username) {
        const cleanUname = username.trim().toLowerCase();
        dom.screensGrid.querySelectorAll('.screen-card').forEach(existingCard => {
            const existingUid = existingCard.dataset.userId || existingCard.id.replace(/^card-/, '');
            const existingUname = (existingCard.dataset.username || '').trim().toLowerCase();
            if (existingUid !== state.userId && existingUid !== userId && existingUname === cleanUname) {
                const oldVid = existingCard.querySelector('video');
                if (oldVid) {
                    oldVid.pause();
                    oldVid.srcObject = null;
                }
                existingCard.remove();
            }
        });
    }

    let card = document.getElementById(`card-${userId}`);
    if (!card) {
        card = document.createElement('div');
        card.id = `card-${userId}`;
        card.className = 'screen-card';
        card.dataset.userId = userId;
        card.dataset.username = username || '';

        card.innerHTML = `
            <video autoplay playsinline ${isMuted ? 'muted' : ''}></video>
            <div class="screen-overlay">
                <div class="screen-tag">
                    <svg class="svg-icon svg-icon-sm" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                        <rect width="20" height="14" x="2" y="3" rx="2"/>
                        <line x1="8" x2="16" y1="21" y2="21"/>
                        <line x1="12" x2="12" y1="17" y2="21"/>
                    </svg>
                    <span class="stream-username">${escapeHtml(username)}</span>
                </div>
                <div class="screen-controls">
                    ${!isMuted ? `
                        <div class="volume-control-wrapper" title="Volume individual desta tela">
                            <button class="screen-btn mute-btn" title="Mutar/Desmutar">${getVolumeSvg(1.0, false)}</button>
                            <input type="range" class="volume-slider" min="0" max="1" step="0.02" value="1">
                            <span class="volume-percent">100%</span>
                        </div>
                        ${state.allowRemoteScreenKick ? `
                            <button class="screen-btn kick-screen-btn" title="Encerrar transmissão deste usuário (kick)">
                                <svg class="svg-icon svg-icon-sm" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                                    <circle cx="12" cy="12" r="10"/>
                                    <line x1="15" y1="9" x2="9" y2="15"/>
                                    <line x1="9" y1="9" x2="15" y2="15"/>
                                </svg>
                            </button>
                        ` : ''}
                    ` : `
                        <div class="screen-tag" style="font-size: 11px; opacity: 0.8;">(Você)</div>
                    `}
                    <button class="screen-btn focus-btn" title="Tela Cheia">
                        <svg class="svg-icon svg-icon-sm" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                            <path d="M8 3H5a2 2 0 0 0-2 2v3"/><path d="M21 8V5a2 2 0 0 0-2-2h-3"/><path d="M3 16v3a2 2 0 0 0 2 2h3"/><path d="M16 21h3a2 2 0 0 0 2-2v-3"/>
                        </svg>
                    </button>
                </div>
            </div>
        `;

        dom.screensGrid.appendChild(card);

        // Ações dos botões do card
        const kickBtn = card.querySelector('.kick-screen-btn');
        if (kickBtn) {
            kickBtn.addEventListener('click', () => {
                const confirmed = confirm(`Deseja realmente encerrar a transmissão de ${username}?`);
                if (confirmed) {
                    sendWs({
                        type: 'KICK_SCREEN',
                        targetUserId: userId
                    });
                }
            });
        }
        const video = card.querySelector('video');
        video.srcObject = stream;

        const focusBtn = card.querySelector('.focus-btn');
        focusBtn.addEventListener('click', () => {
            if (document.fullscreenElement) {
                document.exitFullscreen();
            } else {
                card.requestFullscreen().catch(err => console.error(err));
            }
        });

        if (!isMuted) {
            const muteBtn = card.querySelector('.mute-btn');
            const volumeSlider = card.querySelector('.volume-slider');
            const volumePercent = card.querySelector('.volume-percent');

            let lastVolume = 1.0;

            const applyVolume = (vol) => {
                video.volume = vol;
                video.muted = (vol === 0);
                volumeSlider.value = vol;
                volumePercent.textContent = `${Math.round(vol * 100)}%`;
                muteBtn.innerHTML = getVolumeSvg(vol, video.muted);
            };

            volumeSlider.addEventListener('input', (e) => {
                const vol = parseFloat(e.target.value);
                if (vol > 0) lastVolume = vol;
                applyVolume(vol);
            });

            muteBtn.addEventListener('click', () => {
                if (video.volume > 0 && !video.muted) {
                    lastVolume = video.volume || 1.0;
                    applyVolume(0);
                } else {
                    applyVolume(lastVolume || 1.0);
                }
            });
        }
    } else {
        card.dataset.userId = userId;
        card.dataset.username = username || '';
        const video = card.querySelector('video');
        video.srcObject = stream;
    }
}

function destroyCardElement(card) {
    if (!card) return;
    if (document.fullscreenElement === card) {
        document.exitFullscreen().catch(() => {});
    }
    const video = card.querySelector('video');
    if (video) {
        try {
            video.pause();
            video.srcObject = null;
        } catch (e) {}
    }
    card.remove();
}

function closeSubscriptionEntry(sub) {
    if (!sub) return;
    sub.cancelled = true;
    if (sub.disconnectTimer) {
        clearTimeout(sub.disconnectTimer);
        sub.disconnectTimer = null;
    }
    if (sub.pc) {
        try {
            sub.pc.onconnectionstatechange = null;
            sub.pc.ontrack = null;
            sub.pc.close();
        } catch (e) {}
        sub.pc = null;
    }
}

function removeScreen(userId, username = null) {
    if (userId) {
        const card = document.getElementById(`card-${userId}`);
        destroyCardElement(card);

        const sub = state.subscriptions.get(userId);
        if (sub) {
            closeSubscriptionEntry(sub);
            state.subscriptions.delete(userId);
        }
    }

    // Se username foi informado (e não é o próprio usuário local), limpa também quaisquer cards/inscrições órfãs daquele username
    if (username && userId !== state.userId) {
        const cleanUname = username.trim().toLowerCase();
        for (const [uid, sub] of Array.from(state.subscriptions.entries())) {
            if (sub.username && sub.username.trim().toLowerCase() === cleanUname) {
                closeSubscriptionEntry(sub);
                state.subscriptions.delete(uid);
                destroyCardElement(document.getElementById(`card-${uid}`));
            }
        }
        dom.screensGrid.querySelectorAll('.screen-card').forEach(c => {
            const cUid = c.dataset.userId || c.id.replace(/^card-/, '');
            const cUname = (c.dataset.username || '').trim().toLowerCase();
            if (cUid !== state.userId && cUname === cleanUname) {
                destroyCardElement(c);
            }
        });
    }

    updateGridLayout();
}

function updateGridLayout() {
    const cards = dom.screensGrid.querySelectorAll('.screen-card');
    const count = cards.length;

    if (count === 0) {
        dom.emptyState.classList.remove('hidden');
        dom.screensGrid.classList.add('hidden');
    } else {
        dom.emptyState.classList.add('hidden');
        dom.screensGrid.classList.remove('hidden');
        dom.screensGrid.setAttribute('data-count', Math.min(count, 6).toString());
    }
}

// ==========================================
// 6. Chat e Utilidades
// ==========================================

dom.chatForm.addEventListener('submit', (e) => {
    e.preventDefault();
    const text = dom.chatInput.value.trim();
    if (!text) return;

    sendWs({
        type: 'CHAT_MESSAGE',
        text: text
    });

    dom.chatInput.value = '';
});

function appendChatMessage(msg) {
    const div = document.createElement('div');
    div.className = 'chat-msg';

    const time = new Date(msg.timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    div.innerHTML = `
        <span class="author">${escapeHtml(msg.username)}</span>
        <span class="time">${time}</span>
        <div class="content">${escapeHtml(msg.text)}</div>
    `;

    dom.chatMessages.appendChild(div);
    dom.chatMessages.scrollTop = dom.chatMessages.scrollHeight;

    if (!state.chatOpen) {
        state.unreadMessages++;
        dom.unreadBadge.textContent = state.unreadMessages;
        dom.unreadBadge.classList.remove('hidden');
    }
}

function appendSystemMessage(text) {
    const div = document.createElement('div');
    div.className = 'chat-system-msg';
    div.textContent = text;
    dom.chatMessages.appendChild(div);
    dom.chatMessages.scrollTop = dom.chatMessages.scrollHeight;
}

dom.toggleChatBtn.addEventListener('click', () => {
    state.chatOpen = !state.chatOpen;
    dom.chatSidebar.classList.toggle('collapsed', !state.chatOpen);
    if (state.chatOpen) {
        state.unreadMessages = 0;
        dom.unreadBadge.classList.add('hidden');
    }
});

dom.fullscreenBtn.addEventListener('click', () => {
    if (!document.fullscreenElement) {
        document.documentElement.requestFullscreen().catch(err => console.error(err));
    } else {
        document.exitFullscreen();
    }
});

dom.leaveBtn.addEventListener('click', () => {
    if (confirm("Deseja realmente sair da sala?")) {
        window.location.reload();
    }
});

function updateParticipants(list) {
    if (Array.isArray(list)) {
        state.participants = list;
        if (dom.participantCount) {
            dom.participantCount.textContent = `${list.length} online`;
        }
        renderParticipantsModal();
    }
}

function renderParticipantsModal() {
    if (!dom.participantsList) return;

    const list = Array.isArray(state.participants) && state.participants.length > 0
        ? state.participants
        : (state.username ? [{ userId: state.userId, username: state.username }] : []);

    if (dom.modalParticipantsSubtitle) {
        dom.modalParticipantsSubtitle.textContent = `Sala #${state.room || 'principal'} • ${list.length} online`;
    }

    dom.participantsList.innerHTML = '';

    list.forEach(p => {
        const isMe = p.userId === state.userId;
        const isLive = (isMe && state.isSharing) || state.subscriptions.has(p.userId);
        const initial = (p.username || '?').trim().charAt(0).toUpperCase() || '?';

        const item = document.createElement('div');
        item.className = 'participant-item';
        item.innerHTML = `
            <div class="participant-info">
                <div class="participant-avatar">
                    ${escapeHtml(initial)}
                    <span class="participant-online-dot"></span>
                </div>
                <div class="participant-name">
                    ${escapeHtml(p.username || 'Anônimo')}
                    ${isMe ? '<span class="participant-you-tag">(Você)</span>' : ''}
                </div>
            </div>
            ${isLive ? `
                <span class="participant-live-badge" title="Transmitindo tela agora">
                    <span class="participant-live-dot"></span>
                    AO VIVO
                </span>
            ` : ''}
        `;
        dom.participantsList.appendChild(item);
    });
}

function openParticipantsModal() {
    if (!dom.participantsModal) return;
    renderParticipantsModal();
    dom.participantsModal.classList.remove('hidden');
}

if (dom.openParticipantsBtn) {
    dom.openParticipantsBtn.addEventListener('click', openParticipantsModal);
}

if (dom.participantCount) {
    dom.participantCount.addEventListener('click', openParticipantsModal);
}

if (dom.closeParticipantsModalBtn && dom.participantsModal) {
    dom.closeParticipantsModalBtn.addEventListener('click', () => {
        dom.participantsModal.classList.add('hidden');
    });

    dom.participantsModal.addEventListener('click', (e) => {
        if (e.target === dom.participantsModal) {
            dom.participantsModal.classList.add('hidden');
        }
    });

    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape' && !dom.participantsModal.classList.contains('hidden')) {
            dom.participantsModal.classList.add('hidden');
        }
    });
}

// ==========================================
// 6.1 Contagem de Usuários nas Salas (Dropdown da Tela de Login)
// ==========================================

async function fetchRoomsSummary() {
    if (!dom.roomInput || !dom.modal || dom.modal.classList.contains('hidden')) return;
    try {
        const res = await fetch('/api/settings/rooms-summary');
        if (res.ok) {
            const data = await res.json();
            if (data && data.rooms) {
                Array.from(dom.roomInput.options).forEach(opt => {
                    const roomKey = opt.value;
                    const baseLabel = opt.getAttribute('data-label') || roomKey;
                    const count = data.rooms[roomKey] !== undefined ? Number(data.rooms[roomKey]) : 0;
                    opt.textContent = `${baseLabel} (${count} online)`;
                });
            }
        }
    } catch (err) {
        console.debug("Erro ao buscar resumo de salas:", err);
    }
}

fetchRoomsSummary();
setInterval(fetchRoomsSummary, 4000);

function getQualityConstraints() {
    const choice = dom.qualitySelect ? dom.qualitySelect.value : '1080p60';
    switch (choice) {
        case '1080p60':
            return {
                video: { width: { ideal: 1920, max: 1920 }, height: { ideal: 1080, max: 1080 }, frameRate: { ideal: 60, max: 60 } },
                fps: 60,
                maxBitrate: 15_000_000, // 15 Mbps para 1080p60 fiel sem artefatos
                minBitrateKbps: 5000,
                startBitrateKbps: 10000,
                maxBitrateKbps: 15000
            };
        case '1080p30':
            return {
                video: { width: { ideal: 1920, max: 1920 }, height: { ideal: 1080, max: 1080 }, frameRate: { ideal: 30, max: 30 } },
                fps: 30,
                maxBitrate: 10_000_000, // 10 Mbps para 1080p30 ultra nítido
                minBitrateKbps: 4000,
                startBitrateKbps: 7500,
                maxBitrateKbps: 10000
            };
        case '720p60':
            return {
                video: { width: { ideal: 1280, max: 1280 }, height: { ideal: 720, max: 720 }, frameRate: { ideal: 60, max: 60 } },
                fps: 60,
                maxBitrate: 6_000_000, // 6 Mbps para 720p60
                minBitrateKbps: 2500,
                startBitrateKbps: 4500,
                maxBitrateKbps: 6000
            };
        default:
            return {
                video: { width: { ideal: 1920, max: 1920 }, height: { ideal: 1080, max: 1080 }, frameRate: { ideal: 60, max: 60 } },
                fps: 60,
                maxBitrate: 15_000_000,
                minBitrateKbps: 5000,
                startBitrateKbps: 10000,
                maxBitrateKbps: 15000
            };
    }
}

function preferHighQualityVideoCodecs(transceiver) {
    try {
        if (!transceiver || typeof transceiver.setCodecPreferences !== 'function') return;
        if (!window.RTCRtpSender || typeof RTCRtpSender.getCapabilities !== 'function') return;

        const capabilities = RTCRtpSender.getCapabilities('video');
        if (!capabilities || !Array.isArray(capabilities.codecs) || capabilities.codecs.length === 0) return;

        const scoreCodec = (c) => {
            const mime = (c.mimeType || '').toLowerCase();
            const fmtp = (c.sdpFmtpLine || '').toLowerCase();
            if (mime === 'video/av1') return 1;
            if (mime === 'video/vp9') return fmtp.includes('profile-id=0') ? 2 : 3;
            if (mime === 'video/h264') {
                // Prioriza High Profile (64...) e Main Profile (4d...) sobre Baseline (42...)
                if (fmtp.includes('profile-level-id=64')) return 4;
                if (fmtp.includes('profile-level-id=4d')) return 5;
                return 6;
            }
            if (mime === 'video/vp8') return 7;
            // Mantém codecs auxiliares de reparo/retransmissão (rtx, red, ulpfec)
            return 8;
        };

        const sortedCodecs = [...capabilities.codecs].sort((a, b) => scoreCodec(a) - scoreCodec(b));
        transceiver.setCodecPreferences(sortedCodecs);
    } catch (err) {
        console.debug("Aviso ao definir preferência de codecs:", err);
    }
}

async function applyHighQualitySenderParameters(pc, qualityConfig) {
    if (!pc) return;
    const senders = pc.getSenders ? pc.getSenders() : [];
    for (const sender of senders) {
        if (sender.track && sender.track.kind === 'video') {
            try {
                const params = sender.getParameters();
                if (!params.encodings || params.encodings.length === 0) {
                    params.encodings = [{}];
                }
                // Impede o WebRTC de reduzir a resolução de 1080p para 720p/540p
                params.degradationPreference = 'maintain-resolution';
                params.encodings[0].maxBitrate = qualityConfig.maxBitrate || 15_000_000;
                params.encodings[0].maxFramerate = qualityConfig.fps || 60;
                params.encodings[0].scaleResolutionDownBy = 1.0;
                params.encodings[0].priority = 'high';
                params.encodings[0].networkPriority = 'high';
                await sender.setParameters(params);
            } catch (err) {
                console.debug("Aviso ao ajustar parâmetros do sender de vídeo:", err);
            }
        }
    }
}

function boostSdpBitrate(sdp, qualityConfig) {
    if (!sdp) return sdp;
    const minKbps = qualityConfig.minBitrateKbps || 5000;
    const startKbps = qualityConfig.startBitrateKbps || 10000;
    const maxKbps = qualityConfig.maxBitrateKbps || 15000;

    const lines = sdp.split(/\r?\n/);
    const out = [];
    let inVideoSection = false;

    for (let i = 0; i < lines.length; i++) {
        let line = lines[i];
        if (line.startsWith('m=video')) {
            inVideoSection = true;
            out.push(line);
            continue;
        } else if (line.startsWith('m=')) {
            inVideoSection = false;
        }

        if (inVideoSection && line.startsWith('c=IN')) {
            out.push(line);
            if (i + 1 < lines.length && !lines[i + 1].startsWith('b=AS:')) {
                out.push(`b=AS:${maxKbps}`);
            }
            continue;
        }

        if (inVideoSection && line.startsWith('a=fmtp:') && !line.includes('apt=') && !line.includes('x-google-max-bitrate')) {
            line = `${line};x-google-min-bitrate=${minKbps};x-google-start-bitrate=${startKbps};x-google-max-bitrate=${maxKbps}`;
        }

        out.push(line);
    }

    return out.join('\r\n');
}

function waitForIceGathering(pc) {
    return new Promise((resolve) => {
        if (pc.iceGatheringState === 'complete') {
            resolve();
        } else {
            const checkState = () => {
                if (pc.iceGatheringState === 'complete') {
                    pc.removeEventListener('icegatheringstatechange', checkState);
                    resolve();
                }
            };
            pc.addEventListener('icegatheringstatechange', checkState);
            // Timeout de segurança após 1.5s
            setTimeout(resolve, 1500);
        }
    });
}

function escapeHtml(string) {
    const div = document.createElement('div');
    div.textContent = string;
    return div.innerHTML;
}

// ==========================================
// 7. Monitoramento da Cota Gratuita
// ==========================================

function updateQuotaDisplay(data) {
    if (!data) return;

    const pct = Math.min(100, Math.max(0, data.percentageRemaining));

    // Cabeçalho da aplicação
    if (dom.quotaPercent) {
        dom.quotaPercent.textContent = `${data.percentageRemaining}%`;
    }

    if (dom.quotaRemaining) {
        dom.quotaRemaining.textContent = `(${data.remainingGb} GB)`;
    }

    if (dom.quotaBarFill) {
        dom.quotaBarFill.style.width = `${pct}%`;

        dom.quotaBarFill.classList.remove('green', 'yellow', 'red');
        if (pct > 40) {
            dom.quotaBarFill.classList.add('green');
            if (dom.quotaPercent) dom.quotaPercent.style.color = 'var(--success)';
        } else if (pct > 15) {
            dom.quotaBarFill.classList.add('yellow');
            if (dom.quotaPercent) dom.quotaPercent.style.color = '#f1c40f';
        } else {
            dom.quotaBarFill.classList.add('red');
            if (dom.quotaPercent) dom.quotaPercent.style.color = 'var(--danger)';
        }
    }

    if (dom.quotaWidget) {
        dom.quotaWidget.title = 
            `Consumo da Sala (Mês: ${data.month})\n` +
            `• Utilizado: ${data.usedGb} GB / 1.000 GB\n` +
            `• Saldo livre: ${data.remainingGb} GB (${data.percentageRemaining}%)\n` +
            `• Transmissão atual: ${data.currentRateGbPerHour} GB/h\n` +
            `• Telas ativas: ${data.activeScreens} | Espectadores: ${data.activeViewers}\n` +
            `Clique para abrir o painel detalhado.`;
    }

    // Modal de Detalhes da Cota
    const mPct = document.getElementById('modal-quota-pct');
    const mBar = document.getElementById('modal-quota-bar-fill');
    const mRemLabel = document.getElementById('modal-quota-remaining-label');
    const mUsedGb = document.getElementById('modal-used-gb');
    const mUsedMb = document.getElementById('modal-used-mb');
    const mLiveRate = document.getElementById('modal-live-rate');
    const mActiveViewers = document.getElementById('modal-active-viewers');
    const mEst60 = document.getElementById('modal-est-60fps');
    const mEst30 = document.getElementById('modal-est-30fps');
    const mMonth = document.getElementById('modal-quota-month');

    if (mPct) mPct.textContent = `${data.percentageRemaining}%`;
    if (mBar) mBar.style.width = `${pct}%`;
    if (mRemLabel) mRemLabel.textContent = `${data.remainingGb} GB livres`;
    if (mUsedGb) mUsedGb.textContent = `${data.usedGb} GB`;
    if (mUsedMb) mUsedMb.textContent = `(${data.usedMb} MB)`;
    if (mLiveRate) mLiveRate.textContent = `${data.currentRateGbPerHour} GB/h`;
    if (mActiveViewers) mActiveViewers.textContent = `${data.activeViewers} espectadores (${data.activeScreens} telas)`;
    if (mEst60) mEst60.textContent = `~${Math.round(data.remainingGb / 10.8)} horas`;
    if (mEst30) mEst30.textContent = `~${Math.round(data.remainingGb / 5.4)} horas`;
    if (mMonth) mMonth.textContent = `Mês de Referência: ${data.month}`;
}

async function fetchQuota() {
    if (!state.sessionToken) return;
    try {
        const res = await fetchWithAuth('/api/quota');
        if (res.ok) {
            const data = await res.json();
            updateQuotaDisplay(data);
        }
    } catch (err) {
        console.debug("Erro ao obter cota:", err);
    }
}

// Eventos de Abertura e Fechamento do Modal de Cota
const quotaModal = document.getElementById('quota-modal');
const closeQuotaModalBtn = document.getElementById('close-quota-modal');
const btnSyncCloudflare = document.getElementById('btn-sync-cloudflare');
const syncStatusMsg = document.getElementById('sync-status-msg');

if (dom.quotaWidget && quotaModal) {
    dom.quotaWidget.addEventListener('click', () => {
        quotaModal.classList.remove('hidden');
        fetchQuota();
    });
}

if (closeQuotaModalBtn && quotaModal) {
    closeQuotaModalBtn.addEventListener('click', () => {
        quotaModal.classList.add('hidden');
    });

    quotaModal.addEventListener('click', (e) => {
        if (e.target === quotaModal) {
            quotaModal.classList.add('hidden');
        }
    });

    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape' && !quotaModal.classList.contains('hidden')) {
            quotaModal.classList.add('hidden');
        }
    });
}

const syncSvgIcon = `
    <svg class="svg-icon svg-icon-sm" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
        <path d="M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8"/><path d="M21 3v5h-5"/><path d="M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16"/><path d="M8 16H3v5"/>
    </svg>
`;

if (btnSyncCloudflare) {
    btnSyncCloudflare.addEventListener('click', async () => {
        btnSyncCloudflare.disabled = true;
        btnSyncCloudflare.innerHTML = `${syncSvgIcon} Sincronizando...`;
        try {
            const res = await fetchWithAuth('/api/quota/sync', { method: 'POST' });
            if (res.ok) {
                const data = await res.json();
                updateQuotaDisplay(data);
                if (syncStatusMsg) {
                    syncStatusMsg.textContent = 'Atualizado com sucesso!';
                    setTimeout(() => {
                        syncStatusMsg.textContent = 'Atualizado automaticamente a cada 3 minutos';
                    }, 4000);
                }
            }
        } catch (err) {
            console.error(err);
            if (syncStatusMsg) syncStatusMsg.textContent = 'Falha ao atualizar dados.';
        } finally {
            btnSyncCloudflare.disabled = false;
            btnSyncCloudflare.innerHTML = `${syncSvgIcon} Sincronizar Informações`;
        }
    });
}

// Inicia busca inicial e monitoramento contínuo da cota (apenas se autenticado)
if (state.sessionToken) {
    fetchQuota();
}
setInterval(() => {
    if (state.sessionToken) {
        fetchQuota();
    }
}, 5000);

// Inicializa indicador de qualidade com base na opção selecionada
updateQualityIndicator(null, false);


