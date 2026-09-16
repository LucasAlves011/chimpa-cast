# 🐵 ChimpaCast - Compartilhamento de Tela em Alta Performance para o Bando

Sistema de transmissão de tela de alta performance (*N para N*) focado em qualidade Full HD (1080p a 60fps), áudio do sistema e **delay zero (sub-100ms)**, utilizando a infraestrutura global da **Cloudflare Calls (WebRTC SFU)** com backend de sinalização em **Java (Spring Boot)**.

---

## 🚀 Como Funciona

* **Backend em Java (Spring Boot):** Servidor de sinalização de salas via WebSockets com autenticação e proxy seguro da API da Cloudflare Calls (mantendo seu `APP_SECRET` protegido).
* **Nuvem (Cloudflare Calls):** O tráfego pesado de vídeo e áudio trafega diretamente entre os navegadores e a rede global da Cloudflare via WebRTC UDP, sem sobrecarregar o upload da sua internet caseira.
* **Segurança & Cota Zero:** Monitoramento contínuo com trava automática (Kill-Switch em 990 GB) para assegurar custo zero no tier gratuito da Cloudflare (1 TB/mês).

---

## 🛠️ Passo a Passo para Configurar

### 1. Criar o App na Cloudflare Calls
1. Acesse o painel da [Cloudflare](https://dash.cloudflare.com/).
2. No menu lateral, navegue até **Calls** (ou **Realtime** -> **Calls**).
3. Clique em **Create App** e escolha um nome (ex: `chimpacast`).
4. Copie as credenciais geradas:
   * **App ID**
   * **App Secret**

### 2. Configurar as Credenciais

#### Opção A: Executar com Docker Compose (Porta 8084)
Copie o modelo de variáveis de ambiente e preencha suas credenciais:
```bash
cp .env.example .env
docker compose up -d --build
```
Acesse: `http://localhost:8084`

#### Opção B: Executar Localmente com Maven (Porta 8080)
Defina as variáveis no PowerShell / terminal:
```powershell
$env:CLOUDFLARE_CALLS_APP_ID="seu_app_id"
$env:CLOUDFLARE_CALLS_APP_SECRET="seu_app_secret"
$env:CLOUDFLARE_CALLS_ACCOUNT_ID="seu_account_id"
$env:CLOUDFLARE_CALLS_ANALYTICS_TOKEN="seu_token"
mvn spring-boot:run
```
Ou coloque-as no arquivo local `src/main/resources/application-local.properties` (já ignorado pelo Git).
Acesse: `http://localhost:8080`

---

## 🎮 Recursos Inclusos

* **Qualidade Full HD 60fps:** Otimizado com `contentHint: "motion"` para fluidez máxima em jogos e vídeos.
* **Captura de Áudio do Sistema:** Transmite o áudio do jogo/sistema operacional junto com a tela.
* **Multiusuário ($N \to N$):** Qualquer primata na sala pode transmitir ao mesmo tempo que assiste aos amigos.
* **Modo Tela Cheia / Foco:** Clique no ícone de tela cheia em qualquer vídeo para assisti-lo em tamanho máximo.
* **Controle de Volume Individual:** Permite mutar ou ajustar o áudio de cada tela transmitida de forma independente.
* **Chat Integrado:** Troca de mensagens em tempo real para o bando.
* **Proteção Anti-Estouro (Kill-Switch):** Travamento compulsório e seguro contra cobranças.

---

Desenvolvido por **Lucas Alves** • Use com responsabilidade.
