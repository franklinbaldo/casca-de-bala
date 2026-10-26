# Consulta proprietária explícita, sem inicialização automática

A ação `HuamiGattAdapter.requestBattery()` usa `ReadOnlyBatteryQuery` e o pump
Huami existente. Não é chamada por callbacks de AUTH. Uma Activity somente de
fixtures fornece o botão «Consultar bateria · peer simulado» depois de
PEER_ACCEPTED. O adaptador físico não é instanciado nesses testes e nenhuma
credencial real é aceita pela fixture.

Fluxo implementado: ação explícita → lista de serviços endpoint0/CMD03 → resolução
da flag de cifragem de 0x0029 → consulta 0x0029/CMD03 → resposta04 de21 bytes →
validação pelo decoder Rust via JNI → snapshot local. Não inicializa os serviços
retornados: nenhuma escrita de hora, perfil, alarmes, configuração ou notificação.
Nenhum comando de localizar é despachado. Consulte as fontes fixadas em
[zepp-capabilities-0.6.md](zepp-capabilities-0.6.md) e ZeppOsServicesService no mesmo
commit Gadgetbridge a0948ee1cbc2a870f91d313f8e37df5f524465f7 (AGPL-3.0-or-later).

O mapa de serviços não é presumido a partir do modelo ou anúncio Bluetooth.
Flag desconhecida/serviço ausente retorna UNSUPPORTED; não faz fallback silencioso.
A consulta de serviços usa o caminho inicial plaintext documentado upstream;
a bateria segue a flag reportada. Frames plaintext e cifrados compartilham o
alocador de handles de TX depois de AUTH, evitando colisão entre os dois caminhos.

## Correlação, tempo e limites

A resposta proprietária não contém ID de requisição. A correlação local usa
**geração de conexão + fase + endpoint + opcode**, uma consulta por conexão,
sem prometer distinguir uma resposta espontânea de resposta solicitada do mesmo
endpoint. Depois de timeout/cancelamento não há retry na mesma sessão: requer
reconexão/geração nova. Duplicatas após conclusão são ignoradas pela consulta.
Isso não é proteção criptográfica contra replay: a política RX source-compatible
continua sem verificação de CRC/replay comprovada para o layout real da Band.

Cada fase espera até5s monotônicos, dentro do limite total15s do piloto AUTH.
Uma resposta recebida antes do callback final da escrita é retida em um único
buffer limitado a512 bytes; não dispara a próxima fase até a escrita concluir.
ACKs usam a fila serial existente e têm prioridade. Só há uma escrita GATT em voo.
Endpoints não relacionados não satisfazem a consulta. Callbacks de geração antiga,
falhas de escrita, ausência de callback, clock inválido e cancelamento não criam
resultado. O resultado é copiado, com timestamp monotônico de recepção; o campo de
horário interno da bateria continua bytes brutos, sem inventar UTC/fuso.

O ABI local `b7_zepp`/`NativeBridge.zepp` valida bateria/find/notificações usando os
mesmos decoders Rust. Também enquadra somente CMD03 para endpoint0 ou0x29 no
caminho plaintext. Não é um protocolo BLE novo, não recebe chaves e não retém
ponteiros. Erros deixam saída intacta e tamanho0; memória pertence ao chamador.

## Reprodução e gates

`sh android-bridge/run_crypto_host.sh` com BC_JAR oficial fixado executa contrato
host+BC/JNI/Rust e `BatteryQueryTest`. `cargo test --locked --offline`, rustfmt e
clippy validam o núcleo. Os scripts de build x86_64/DEX e a instrumentation mode
`battery` permitem o mesmo teste no Android; ela executa também os decoders find
 e notification por JNI, sem enviar essas operações. O modo `cancel` é regressão
focalizada do ciclo afetado. Recibos privados registram os resultados reais e os
hashes de cada APK/DEX/nativo; build não equivale a execução no ART.

Para um APK piloto físico ainda faltam entrada local segura de credencial sob
consentimento, seleção/ação explicitamente integrada à interface de produto e
validação da sessão/autenticação na unidade. A fixture não valida DH com peer
independente nem prova interoperabilidade: compõe vetores públicos conhecidos.
A lista/cifragem e bateria da unidade têm de ser comprovadas em teste separado,
sem inicialização de hora/perfil, fetch de saúde ou comando de vibração.
O APK de teste x86_64 é exclusivo do emulador, não instalador para Franklin.

## Resultado desta rodada

PASS: peer sintético no host e no ART/API31 x86_64 com BC1.86 + JNI/Rust reais;
Activity instalada, ação de bateria, nenhuma consulta automática, leitura74 de
fixture, bloqueio de repetição, rotação, cancelamento/reconexão e ausência de
callback; regressão focalizada de cancelamento com oito pares antes/depois.
SystemServer permaneceu PID541 e crash buffer não mudou. Regressores básicos
host/JNI e núcleo Rust são registrados separadamente no recibo.

O novo pacote `.batteryfixture` e seus arquivos temporários foram removidos;
o APK anterior no AVD foi preservado e seu hash conferido. Emulador/servidor
próprios encerrados, identidade ADB e keystore de teste apagados. Nada foi
instalado no celular, enviado à Band ou publicado. A suíte Activity completa não
foi repetida; os casos afetados foram executados explicitamente.

`aapt dump badging` não conseguiu resolver o ícone de recurso Android do app
(exit1); não foi contado como PASS. O manifesto compilado foi inspecionado com
`aapt2 dump xmltree` (PASS), preservando testOnly e somente as duas permissões
Bluetooth existentes. Assinatura, alinhamento, instalação e execução reais desse
APK no emulador passaram. O problema do dumper não foi ocultado nem tratado como
falha de instalação, e não motivou alteração de UI/permissão fora do recorte.

## Interface posterior

A UI e entrada efêmera local foram preparadas depois desta rodada: ver
[product-pilot-0.6.md](product-pilot-0.6.md). Isso não muda o NOT_RUN físico nem
a necessidade de decidir assinatura distribuível antes de um instalador piloto.
