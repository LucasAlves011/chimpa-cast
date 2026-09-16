/**
 * ChimpaCast - Frontend WebRTC & WebSocket Client
 * Plataforma de compartilhamento de tela de alta performance para primatas
 */

const state = {
    userId: 'user-' + Math.random().toString(36).substring(2, 9),
    username: '',
    room: 'principal',
    password: '',
    sessionToken: sessionStorage.getItem('chimpacast_token') || sessionStorage.getItem('streamflow_token') || '',
    quotaAvailable: true,
    authFailed: false,
    ws: null,
    isSharing: false,
    localStream: null,
    publisherPc: null,
    publisherSessionId: null,
    // Mapa de inscrições ativas: userId -> { pc, sessionId, container }
    subscriptions: new Map(),
    unreadMessages: 0,
    chatOpen: true
};

// Elementos DOM
const dom = {
    modal: document.getElementById('join-modal'),
    joinForm: document.getElementById('join-form'),
    usernameInput: document.getElementById('username-input'),
    roomInput: document.getElementById('room-input'),
    passwordInput: document.getElementById('password-input'),
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
    btnCutoffOk: document.getElementById('btn-cutoff-ok')
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
        return `<svg class="svg-icon svg-icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"/>
            <line x1="22" x2="16" y1="9" y2="15"/>
            <line x1="16" x2="22" y1="9" y2="15"/>
        </svg>`;
    } else if (vol < 0.5) {
        return `<svg class="svg-icon svg-icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"/>
            <path d="M15.54 8.46a5 5 0 0 1 0 7.07"/>
        </svg>`;
    } else {
        return `<svg class="svg-icon svg-icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"/>
            <path d="M15.54 8.46a5 5 0 0 1 0 7.07"/>
            <path d="M19.07 4.93a10 10 0 0 1 0 14.14"/>
        </svg>`;
    }
}

// Requisições HTTP com Cabeçalho de Proteção por Token ou Senha
function fetchWithAuth(url, options = {}) {
    const headers = { ...(options.headers || {}) };
    if (state.sessionToken) {
        headers['X-Session-Token'] = state.sessionToken;
    } else if (state.password) {
        headers['X-Access-Password'] = state.password;
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
// 1. Inicialização e Entrada na Sala
// ==========================================

dom.joinForm.addEventListener('submit', async (e) => {
    e.preventDefault();
    clearJoinError();

    const username = dom.usernameInput.value.trim();
    const room = dom.roomInput.value.trim().toLowerCase();
    const password = dom.passwordInput ? dom.passwordInput.value.trim() : '';

    if (!username || !room) return;
    if (!password) {
        showJoinError("Por favor, informe a senha de acesso da plataforma.");
        return;
    }

    state.authFailed = false;

    // Feedback visual no botão durante a checagem
    const submitBtn = dom.joinForm.querySelector('button[type="submit"]');
    const originalBtnText = submitBtn ? submitBtn.textContent : 'Entrar na Sala';
    if (submitBtn) {
        submitBtn.disabled = true;
        submitBtn.textContent = 'Verificando senha...';
    }

    // 1. Validação prévia da senha no backend
    try {
        const authRes = await fetch('/api/auth/validate', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ password })
        });

        let authData = {};
        try {
            authData = await authRes.json();
        } catch (jsonErr) {
            console.warn("Resposta não é JSON:", jsonErr);
        }

        if (authRes.status === 429) {
            showJoinError(authData.message || "Muitas tentativas de login! Por favor, aguarde 1 minuto.");
            if (submitBtn) {
                submitBtn.disabled = false;
                submitBtn.textContent = originalBtnText;
            }
            return;
        }

        if (!authRes.ok || !authData.valid) {
            state.authFailed = true;
            showJoinError(authData.message || "Senha de acesso incorreta! Verifique com o administrador.");
            if (submitBtn) {
                submitBtn.disabled = false;
                submitBtn.textContent = originalBtnText;
            }
            return;
        }

        // Sucesso na autenticação: armazena APENAS o token opaco de sessão
        state.sessionToken = authData.token || '';
        sessionStorage.setItem('chimpacast_token', state.sessionToken);
        sessionStorage.removeItem('streamflow_token');
        sessionStorage.removeItem('streamflow_pwd');
    } catch (err) {
        console.error("Erro na validação de login:", err);
        showJoinError("Não foi possível validar as credenciais com o servidor. Verifique se o backend está ativo.");
        if (submitBtn) {
            submitBtn.disabled = false;
            submitBtn.textContent = originalBtnText;
        }
        return;
    } finally {
        if (submitBtn && !submitBtn.disabled) {
            submitBtn.textContent = originalBtnText;
        }
    }

    // Se chegou aqui, a autenticação foi aprovada pelo backend
    soundManager.init();

    state.username = username;
    state.room = room;
    state.password = password;
    state.authFailed = false;

    dom.displayUsername.textContent = username;
    dom.displayRoomName.textContent = room;

    dom.modal.classList.add('hidden');
    dom.app.classList.remove('hidden');

    if (submitBtn) {
        submitBtn.disabled = false;
        submitBtn.textContent = originalBtnText;
    }

    connectWebSocket();
    fetchQuota();
});

function connectWebSocket() {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const authQuery = state.sessionToken
        ? `?token=${encodeURIComponent(state.sessionToken)}`
        : (state.password ? `?password=${encodeURIComponent(state.password)}` : '');
    const wsUrl = `${protocol}//${window.location.host}/ws/rooms${authQuery}`;

    dom.statusText.textContent = "Conectando...";
    state.ws = new WebSocket(wsUrl);

    state.ws.onopen = () => {
        dom.statusText.textContent = "Conectado";
        // Envia mensagem para entrar na sala com token de sessão seguro
        sendWs({
            type: 'JOIN_ROOM',
            room: state.room,
            username: state.username,
            userId: state.userId,
            token: state.sessionToken,
            password: state.password
        });
    };

    state.ws.onmessage = async (event) => {
        try {
            const data = JSON.parse(event.data);
            handleWsMessage(data);
        } catch (err) {
            console.error("Erro ao analisar mensagem WebSocket:", err);
        }
    };

    state.ws.onclose = (event) => {
        dom.statusText.textContent = "Desconectado";
        
        // Se a conexão foi rejeitada pelo servidor por falha de autenticação (4001, 1003 ou 1008/1000 com erro)
        if (event.code === 4001 || event.code === 1003 || event.code === 1008 || state.authFailed) {
            console.warn("WebSocket rejeitado por falha de autenticação. Interrompendo reconexões.");
            state.authFailed = true;
            state.sessionToken = '';
            state.password = '';
            sessionStorage.removeItem('chimpacast_token');
            sessionStorage.removeItem('streamflow_token');
            sessionStorage.removeItem('streamflow_pwd');
            dom.modal.classList.remove('hidden');
            dom.app.classList.add('hidden');
            showJoinError("Acesso rejeitado: sessão expirada ou inválida! Por favor, entre novamente.");
            return;
        }

        // Reconecta apenas se o usuário estiver autenticado e na tela da sala
        if (dom.modal.classList.contains('hidden') && (state.sessionToken || state.password) && !state.authFailed) {
            console.warn("WebSocket desconectado. Tentando reconectar em 3s...");
            setTimeout(() => {
                if (dom.modal.classList.contains('hidden') && (state.sessionToken || state.password) && !state.authFailed) {
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
        case 'ROOM_STATE':
            updateParticipants(msg.participants);
            if (msg.screens && Array.isArray(msg.screens)) {
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
            } else if (!state.isSharing) {
                updateQualityIndicator(null, false);
            }
            updateGridLayout();
            break;

        case 'USER_JOINED':
            appendSystemMessage(`${msg.username} entrou na sala.`);
            break;

        case 'USER_LEFT':
            appendSystemMessage(`${msg.username} saiu da sala.`);
            removeScreen(msg.userId);
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
            updateGridLayout();
            break;

        case 'SCREEN_STOPPED':
            soundManager.playScreenShareStop();
            appendSystemMessage(`Transmissão de tela finalizada.`);
            removeScreen(msg.userId);
            if (state.subscriptions.size === 0 && !state.isSharing) {
                updateQualityIndicator(null, false);
            }
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
            for (const [uid] of state.subscriptions) {
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
            state.sessionToken = '';
            state.password = '';
            sessionStorage.removeItem('chimpacast_token');
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

        // Otimização crucial: sinaliza para o encoder dar prioridade de movimento (60fps)
        const [videoTrack] = state.localStream.getVideoTracks();
        if (videoTrack) {
            videoTrack.contentHint = "motion";
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

        // 4. Adiciona tracks no PeerConnection
        const transceivers = [];
        state.localStream.getTracks().forEach((track) => {
            const transceiver = state.publisherPc.addTransceiver(track, { direction: 'sendonly' });
            transceivers.push({ transceiver, track });
        });

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

        // 7. Define a resposta (Answer) da Cloudflare
        if (tracksData.sessionDescription) {
            await state.publisherPc.setRemoteDescription(new RTCSessionDescription(tracksData.sessionDescription));
        }

        state.isSharing = true;
        dom.shareBtn.classList.add('active');
        dom.shareBtnText.textContent = "Parar de Compartilhar";

        const currentQualityChoice = dom.qualitySelect ? dom.qualitySelect.value : '1080p60';
        const currentQualityLabel = getQualityFriendlyName(currentQualityChoice);
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
    if (state.localStream) {
        state.localStream.getTracks().forEach(t => t.stop());
        state.localStream = null;
    }

    if (state.publisherPc) {
        state.publisherPc.close();
        state.publisherPc = null;
    }

    state.isSharing = false;
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
    if (state.subscriptions.has(remoteUserId)) return;

    try {
        // 1. Cria uma sessão na Cloudflare Calls para o espectador
        const sessionRes = await fetchWithAuth('/api/calls/sessions/new', { method: 'POST' });
        if (!sessionRes.ok) {
            const errData = await sessionRes.json().catch(() => ({ error: 'Falha ao iniciar sessão de espectador' }));
            throw new Error(errData.error || `Erro HTTP ${sessionRes.status}`);
        }
        const sessionData = await sessionRes.json();
        const subscriberSessionId = sessionData.sessionId;

        // 2. Cria PeerConnection para receber a mídia
        const subscriberPc = new RTCPeerConnection({
            iceServers: [{ urls: 'stun:stun.cloudflare.com:3478' }]
        });

        const remoteStream = new MediaStream();

        subscriberPc.ontrack = (event) => {
            remoteStream.addTrack(event.track);
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

        // 5. Cloudflare devolve uma Oferta SDP; aplicamos e geramos uma Resposta (Answer)
        if (subTracksData.sessionDescription) {
            await subscriberPc.setRemoteDescription(new RTCSessionDescription(subTracksData.sessionDescription));
            const answer = await subscriberPc.createAnswer();
            await subscriberPc.setLocalDescription(answer);

            // Aguarda candidatos ICE
            await waitForIceGathering(subscriberPc);

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

        // Renderiza o card do vídeo remoto na grade
        renderScreenCard(remoteUserId, screen.username, remoteStream, false);

        state.subscriptions.set(remoteUserId, {
            pc: subscriberPc,
            sessionId: subscriberSessionId
        });

        updateGridLayout();

    } catch (err) {
        console.error(`Erro ao assinar tela de ${screen.username}:`, err);
    }
}

// ==========================================
// 5. Renderização da Interface e Cards de Vídeo
// ==========================================

function renderScreenCard(userId, username, stream, isMuted = false) {
    let card = document.getElementById(`card-${userId}`);
    if (!card) {
        card = document.createElement('div');
        card.id = `card-${userId}`;
        card.className = 'screen-card';

        card.innerHTML = `
            <video autoplay playsinline ${isMuted ? 'muted' : ''}></video>
            <div class="screen-overlay">
                <div class="screen-tag">
                    <svg class="svg-icon svg-icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                        <rect width="20" height="14" x="2" y="3" rx="2"/>
                        <line x1="8" x2="16" y1="21" y2="21"/>
                        <line x1="12" x2="12" y1="17" y2="21"/>
                    </svg>
                    <span class="stream-username">${username}</span>
                </div>
                <div class="screen-controls">
                    ${!isMuted ? `
                        <div class="volume-control-wrapper" title="Volume individual desta tela">
                            <button class="screen-btn mute-btn" title="Mutar/Desmutar">${getVolumeSvg(1.0, false)}</button>
                            <input type="range" class="volume-slider" min="0" max="1" step="0.02" value="1">
                            <span class="volume-percent">100%</span>
                        </div>
                    ` : `
                        <div class="screen-tag" style="font-size: 11px; opacity: 0.8;">(Você)</div>
                    `}
                    <button class="screen-btn focus-btn" title="Tela Cheia">
                        <svg class="svg-icon svg-icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                            <path d="M8 3H5a2 2 0 0 0-2 2v3"/><path d="M21 8V5a2 2 0 0 0-2-2h-3"/><path d="M3 16v3a2 2 0 0 0 2 2h3"/><path d="M16 21h3a2 2 0 0 0 2-2v-3"/>
                        </svg>
                    </button>
                </div>
            </div>
        `;

        dom.screensGrid.appendChild(card);

        // Ações dos botões do card
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
        const video = card.querySelector('video');
        video.srcObject = stream;
    }
}

function removeScreen(userId) {
    const card = document.getElementById(`card-${userId}`);
    if (card) {
        card.remove();
    }

    const sub = state.subscriptions.get(userId);
    if (sub) {
        if (sub.pc) sub.pc.close();
        state.subscriptions.delete(userId);
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
        dom.participantCount.textContent = `${list.length} online`;
    }
}

function getQualityConstraints() {
    const choice = dom.qualitySelect.value;
    switch (choice) {
        case '1080p60':
            return {
                video: { width: { ideal: 1920, max: 1920 }, height: { ideal: 1080, max: 1080 }, frameRate: { ideal: 60, max: 60 } }
            };
        case '1080p30':
            return {
                video: { width: { ideal: 1920, max: 1920 }, height: { ideal: 1080, max: 1080 }, frameRate: { ideal: 30, max: 30 } }
            };
        case '720p60':
            return {
                video: { width: { ideal: 1280, max: 1280 }, height: { ideal: 720, max: 720 }, frameRate: { ideal: 60, max: 60 } }
            };
        default:
            return {
                video: { width: { ideal: 1920 }, height: { ideal: 1080 }, frameRate: { ideal: 60 } }
            };
    }
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
    if (!state.sessionToken && !state.password) return;
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
    <svg class="svg-icon svg-icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
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
if (state.sessionToken || state.password) {
    fetchQuota();
}
setInterval(() => {
    if (state.sessionToken || state.password) {
        fetchQuota();
    }
}, 5000);

// Inicializa indicador de qualidade com base na opção selecionada
updateQualityIndicator(null, false);


