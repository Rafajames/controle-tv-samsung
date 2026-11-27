# Controle TV Samsung

Controle remoto Android para TVs Samsung: por **infravermelho** (celulares com IR, como o Poco F3) ou **Wi‑Fi** (Tizen 2016+). Tem atalhos de apps (Netflix, Prime Video, Disney+, Crunchyroll, Xbox e outros que você adicionar).

**Baixar:** https://github.com/Rafajames/controle-tv-releases/releases/latest/download/controle-tv.apk

## Primeiro uso
1. O celular e a TV precisam estar no mesmo Wi‑Fi.
2. Abra o app: ele procura a TV sozinho (ou toque em ⚙ e digite o IP).
3. Na TV, aceite o aviso **Permitir** (só na primeira vez).

## Ligar a TV pelo app (Wake‑on‑LAN)
- Na TV: Configurações → Geral e Privacidade → Rede → Configurações avançadas → **Ligar com dispositivo móvel** = Ativado.
- Conecte o app pelo menos uma vez com a TV ligada, para ele aprender o endereço MAC dela.

Cada push no `main` gera um APK novo no repositório público [controle-tv-releases](https://github.com/Rafajames/controle-tv-releases/releases) (precisa do secret `RELEASES_TOKEN`).
