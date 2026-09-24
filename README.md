# NFC Writer OTG — leitor/gravador de tags via PN532 + CH340 + USB-OTG

App Android que fala com o módulo **PN532** (ligado por serial num adaptador
**USB-TTL CH340**) conectado ao celular via **cabo OTG**. Lê o UID de tags
NFC e grava um link (URL) nelas em formato NDEF — feito pra você gravar as
plaquinhas de avaliação na frente do cliente, sem depender do NFC nativo
do celular.

## O que já está pronto

- `Pn532Usb.kt` — implementação do protocolo do PN532 (frames de comando,
  leitura de UID, gravação de URL em NDEF em tags NTAG213/215/216 e Mifare
  Ultralight).
- `MainActivity.kt` — tela única: botão de conectar, ler tag, campo de URL
  e botão de gravar.
- Manifesto já configurado com a permissão de USB Host e um filtro para o
  CH340, para o Android sugerir abrir o app automaticamente ao plugar o
  cabo.

Isso é um **ponto de partida funcional**, não um produto pronto — é bem
provável que precise ajustar algum detalhe na hora de testar com o hardware
real na mão (timeouts, alguma variação de firmware do PN532, etc). Isso é
normal em projeto de hardware + software feito do zero.

## Como abrir e rodar

1. Instale o **Android Studio** (gratuito, baixe em developer.android.com/studio)
   no seu computador — é onde o projeto é compilado, não dá pra gerar o
   instalável (.apk) direto no celular.
2. Abra o Android Studio → **Open** → selecione a pasta `NFCWriterOTG`
   (a pasta que contém o arquivo `settings.gradle.kts`).
3. Aguarde o Gradle baixar as dependências (primeira vez demora alguns
   minutos, precisa de internet).
4. Ligue o Moto G05 no computador por cabo USB comum, com a
   **Depuração USB** ativada:
   - Configurações → Sobre o telefone → toque 7x em "Número da versão"
     (ativa o modo desenvolvedor)
   - Configurações → Sistema → Opções do desenvolvedor → ative
     "Depuração USB"
5. No Android Studio, clique no botão verde ▶ (Run) com o celular
   selecionado como dispositivo. O app instala e abre sozinho no celular.
6. Depois desse primeiro teste, pode até desconectar do PC — o app já
   fica instalado no celular normalmente.

## Como testar com o hardware

1. Monte a ligação física (já combinamos isso antes):
   - PN532 VCC → CH340 VCC 5V (jumper do CH340 na posição 5V)
   - PN532 GND → CH340 GND
   - PN532 TXD → CH340 RXD
   - PN532 RXD → CH340 TXD
   - Confirme a chavinha de modo do PN532 na posição **HSU**
2. Plugue o CH340 no cabo OTG e o cabo OTG no celular.
3. Abra o app **NFC Writer OTG**.
4. Toque em **"Conectar módulo"**.
   - Deve pedir permissão de acesso ao dispositivo USB — aceite.
   - Se aparecer "Módulo OK (IC=... Firmware=...)", a comunicação com o
     PN532 está funcionando.
5. Aproxime uma tag NFC (NTAG213 é a mais comum e barata) da antena do
   PN532 e toque em **"Ler tag"** — deve mostrar o UID dela.
6. Digite o link no campo (ex: a URL do seu Worker,
   `https://round-fog-86fa.denispinha920.workers.dev/q/A001`), aproxime a
   tag e toque em **"Gravar na tag"**.
7. Teste aproximando essa mesma tag de **outro celular com NFC** — deve
   aparecer uma notificação com o link, exatamente como as plaquinhas
   comerciais que você viu no mercado.

## Se der erro

- **"Nenhum dispositivo serial USB encontrado"** → confira se o cabo OTG
  suporta dados (alguns cabos baratos só carregam, não transmitem dados) e
  se o Moto G05 realmente ficou com modo host ativo (teste com o pendrive
  como combinamos).
- **"Erro ao falar com o PN532"** → confira a fiação, principalmente se TX
  e RX não estão invertidos, e se a chavinha de modo está em HSU.
- **Grava mas o celular de teste não reconhece o link** → pode ser
  necessário ajustar o número da página inicial de escrita (algumas tags
  usam página 4, outras variam) — me chama que ajustamos juntos olhando o
  datasheet da tag específica que você está usando.

## Próximos passos possíveis (quando quiser evoluir)

- Emitir um som/vibração ao concluir a gravação.
- Guardar no celular um histórico de quais códigos (A001, A002...) já
  foram gravados em qual estabelecimento.
- Trocar a tela por algo mais bonito (Material 3), já que isso é só o
  esqueleto funcional.
