# Ponte de identificação Java/JNI/Rust

`GattBasics.process(byte[])` executa o plano e os decoders de
[identificação básica](gatt-identification.md) através do JNI real existente.
Não importa classes Android, não pede permissões nem chama BluetoothGatt.
Isto fecha o caminho executável de **envelope sintético → Java → JNI → Rust →
resposta** com JVM host. Há adaptador Android compilado e leitura básica0.3
relatada por captura; o snapshot posterior e suas APIs continuam sem teste ART.

## Contrato v1

Entrada/saída UTF-8 JSON, até 32768 bytes cada, sem terminador NUL. `version=1`,
`id` inteiro sem sinal de 64 bits. Chaves desconhecidas, duplicatas de campos
tipados, enums inválidos, UTF-8 inválido e bytes fora de 0..255 são recusados.
Envelope inválido retorna `id:null,error:invalid_input`; versão não suportada
mantém id e retorna `unsupported_version`. Erros semânticos preservam o id.
Não é JSON-RPC/MCP nem um protocolo BLE; é o contrato interno do núcleo.

Exemplo **sintético** de interpretação, usando os mesmos valores técnicos já
permitidos na evidência sanitizada, sem alegar leitura pelo aplicativo:

```json
{"version":1,"id":42,"command":{"op":"interpret","kind":"battery","reader":"synthetic","outcome":{"state":"gatt","status":0,"bytes":[91]}}}
```

Resposta:

```json
{"version":1,"id":42,"result":{"read":{"read":{"reader":"synthetic","kind":"battery","value":{"battery_percent":91}}}}}
```

`kind`: `hardware_revision`, `software_revision`, `pnp_id`, `battery`.
`reader`: `synthetic`, `user_nrf_connect`, `our_app` — origem declarada pelo
chamador, sem atestação. Usar `our_app` não prova que houve callback ou hardware.
`outcome`: `{"state":"not_attempted"}`, `{"state":"permission_denied"}` ou
`{"state":"gatt","status":0,"bytes":[...]}`. Status diferente de zero prevalece
sobre o payload. Dados inválidos ficam `invalid_value`; nunca viram leitura válida.
Os valores decodificados e erros são os mesmos de `src/gatt_basics.rs`.

Exemplo de plano: 6159 = serviço 0x180F, 10777 = característica 0x2A19:

```json
{"version":1,"id":43,"command":{"op":"plan","connect_permission":true,"discovery":[{"service":{"bluetooth16":6159},"characteristic":{"bluetooth16":10777},"readable":true}]}}
```

Resposta: `{"version":1,"id":43,"result":{"plan":[{"read":"battery"}]}}`.
Máximo 128 entradas, decisões na ordem recebida. `bluetooth16` só pode ser
construído pelo futuro adaptador após verificar a base Bluetooth canônica do
UUID completo; `vendor128` recebe um array de exatamente 16 bytes. A fronteira
não recebe o UUID Android original, portanto não pode atestar a canonicalização.
Não reduza o sufixo proprietário a UUID SIG. O plano continua excluindo HID,
serial/System ID, HR e operações fora das quatro leituras básicas. O booleano de
CONNECT é uma observação do chamador, não concessão de permissão do sistema.

## Memória, limites e execução

API síncrona sem handles, estado compartilhado ou persistência. O chamador possui
as arrays Java e não pode modificar entrada durante a chamada; JNI faz cópia por
GetByteArrayRegion, sem pinning ou referência retida. Saída é nova byte[]. Evita
NewStringUTF/modified UTF-8: revisões UTF-8 passam como bytes sem conversão C.
Buffers temporários JNI são limpos e liberados em todos os caminhos normais.
Alocação/erro da fronteira nativa retorna null, convertido pela API gerenciada em
IllegalStateException; erros JSON retornam envelope. Entrada nula/vazia/grande
demais gera IllegalArgumentException antes do JNI. Exceções JVM pendentes são
preservadas. A função C `b7_gatt_json` usa buffers do chamador e `written`, sem
alocação exportada; códigos e precondições constam em ffi/band7.h.

O limite de bytes restringe parsing/alocação; plano limita a 128 entradas.
Não há timeout ou cancelamento de GATT aqui: nenhuma operação assíncrona existe
nesta fronteira pura. Não toca SQLite ou mutex/handles de ActivitySession.

## Reprodução e próxima prova

```sh
# Execute na raiz do projeto
cargo test --offline
cargo fmt --all -- --check
cargo clippy --offline --all-targets -- -D warnings
sh android-bridge/run_host.sh
sh android-bridge/run_worker_host.sh
```

`GattBridgeSmoke` usa exclusivamente envelopes sintéticos e JNI/Rust reais:
revisões UTF-8, PnP, bateria, HID, erros/permissões, limite e 32 chamadas concorrentes
com correlação. Testes Rust adicionais verificam schema estrito, capacidade de
buffer, saída intocada em falhas e ownership. O script também reexecuta o fluxo
Java/JNI/SQLite anterior com banco sintético. Headers host continuam um subset
Linux x86_64 validado na JVM, não substituem jni.h oficial do NDK.

Próximos gates: exercitar o snapshot novo em ART e definir transporte MCP
controlado. Autenticação Huami e fetch de histórico permanecem separados.
Product ID não seleciona deviceSource; miniapp permanece installable:false.

## Extensão local posterior — snapshot técnico

[Contrato e reprodução](basic-snapshot.md) do snapshot já lido: Java/JNI/Rust e
CLI --gatt-json. Não estabelece transporte MCP externo ou nova leitura física.
