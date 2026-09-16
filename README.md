# 📺 StreamFlow - Compartilhamento de Tela em Alta Performance (Cloudflare Calls + Java)

Sistema de transmissão de tela de alta performance (*N para N*) focado em qualidade Full HD (1080p a 60fps), áudio do sistema e **delay zero (sub-100ms)**, utilizando a infraestrutura global da **Cloudflare Calls (WebRTC SFU)** com backend de sinalização em **Java (Spring Boot)**.

---

## 🚀 Como Funciona

* **Backend em Java (Spring Boot):** Roda no seu notebook e atua como servidor de sinalização das salas (via WebSockets) e proxy seguro da API da Cloudflare Calls (mantendo seu `APP_SECRET` protegido).
* **Nuvem (Cloudflare Calls):** O tráfego pesado de vídeo e áudio trafega diretamente entre os navegadores e a rede global da Cloudflare via WebRTC UDP, sem sobrecarregar o upload da sua internet caseira.
* **Acesso Externo (Cloudflare Tunnel):** Expõe a aplicação web para seus amigos com domínio personalizado e certificado HTTPS (obrigatório para que o navegador permita captura de tela).

---

## 🛠️ Passo a Passo para Configurar

### 1. Criar o App na Cloudflare Calls
1. Acesse o painel da [Cloudflare](https://dash.cloudflare.com/).
2. No menu lateral, navegue até **Calls** (ou **Realtime** -> **Calls**).
3. Clique em **Create App** e escolha um nome (ex: `screenshare`).
4. Copie as credenciais geradas:
   * **App ID**
   * **App Secret**

### 2. Configurar as Credenciais no Notebook
Você pode exportar como variáveis de ambiente antes de rodar ou inseri-las diretamente em `src/main/resources/application.properties`:

#### Opção A: Variáveis de Ambiente (PowerShell / Windows)
```powershell
$env:CLOUDFLARE_CALLS_APP_ID="seu_app_id_aqui"
$env:CLOUDFLARE_CALLS_APP_SECRET="seu_app_secret_aqui"
```

#### Opção B: Direto no `src/main/resources/application.properties`
```properties
cloudflare.calls.app-id=seu_app_id_aqui
cloudflare.calls.app-secret=seu_app_secret_aqui
```

---

## ▶️ Como Executar a Aplicação

No terminal, na pasta do projeto:

```bash
mvn spring-boot:run
```

A aplicação iniciará na porta **8080**:
* Acesse localmente em: `http://localhost:8080`

---

## 🌐 Conectando com o seu Cloudflare Tunnel

Para que seus amigos possam acessar de qualquer lugar e para que o navegador libere a permissão de captura de tela (`getDisplayMedia`), aponte o seu Cloudflare Tunnel existente para a porta local `8080`:

1. No painel **Cloudflare Zero Trust** -> **Networks** -> **Tunnels** (ou no seu arquivo de configuração `config.yml` do `cloudflared`):
2. Adicione um **Public Hostname**:
   * **Subdomínio:** `stream.seudominio.com` (ou o nome que preferir)
   * **Service Type:** `HTTP`
   * **URL:** `localhost:8080`
3. Pronto! Ao acessar `https://stream.seudominio.com`, você e seus amigos já poderão entrar na mesma sala e compartilhar tela simultaneamente.

---

## 🎮 Recursos Inclusos

* **Qualidade Full HD 60fps:** Otimizado com `contentHint: "motion"` para fluidez máxima em jogos e vídeos.
* **Captura de Áudio do Sistema:** Transmite o áudio do jogo/sistema operacional junto com a tela.
* **Multiusuário ($N \to N$):** Qualquer pessoa na sala pode transmitir ao mesmo tempo que assiste aos amigos.
* **Modo Tela Cheia / Foco:** Clique no ícone de tela cheia em qualquer vídeo para assisti-lo em tamanho máximo.
* **Controle de Volume Individual:** Permite mutar ou ajustar o áudio de cada tela transmitida de forma independente.
* **Chat Integrado:** Troca de mensagens em tempo real na sala.
