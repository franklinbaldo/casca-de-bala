# Snapshot técnico local Android → JNI → Rust → terminal

25/10/2026. Contrato interno local, sem transporte MCP remoto.

O ponto de entrada `MainActivity.snapshotForTerminal(requestId)` recebe o estado
imutável da última leitura da tela, no thread principal. Reutiliza
`BasicSnapshot.process` → `GattBasics.process` → JNI existente → `b7_gatt_json`.
Não inicia descoberta, conexão, leitura, pareamento, autenticação ou escrita.
É API interna ao processo, não componente Android exportado ou servidor MCP.
Sem snapshot termina com IllegalStateException; leitura ainda em andamento também
é recusada. O cliente futuro deve tratar esses estados antes de chamar.

A fronteira já definida em [gatt-bridge.md](gatt-bridge.md) é JSON UTF-8 v1
limitado a 32768 bytes, com ID e ownership de buffers existentes. Foi estendida,
não substituída. O repositório não define schema FlatBuffers para esta fronteira;
[architecture.md](architecture.md) trata a escolha binária como medição futura.
B7VM é bytecode independente, não o transporte destes dados nem o protocolo BLE.
Não há dependência ou serializer novo.

## Contrato

`command.op = snapshot`, com `snapshot` contendo:

- `reader`: enum existente synthetic, our_app ou user_nrf_connect. Declaração do
  chamador, não atestação. O adaptador Android usa our_app; testes usam synthetic.
- `stage`: complete, partial, empty, failed ou interrupted; estados em andamento
  não são exportados. Complete significa apenas os quatro campos básicos lidos,
  sem erro de encerramento, nunca coleta completa ou conexão permanente.
- `cause`: enum de encerramento da apresentação, preservado inclusive em parcial.
- `read_at_unix_ms`: horário de parede do celular da última leitura bem-sucedida,
  em milissegundos Unix, ou null se nenhuma. Não é relógio da Band, horário de
  cada campo, duração monotônica ou garantia de frescor. Replay não atualiza hora.
- `fields`: battery, hardware, software e pnp; cada um com state e value.
  Estados: read, not_present, not_read, invalid, denied, gatt_error. Valor não nulo
  só é permitido em read. Bateria ausente permanece null, distinta de 0%.

PnP conserva vendor_source, vendor_id, product_id e product_version_bcd; o modelo
Java anterior descartava vendor_source. Agora o construtor recebe explicitamente
os quatro campos, sem assumir a origem. Product ID não vira deviceSource.
Validação Rust reaproveita os decoders de bateria, revisão e PnP. Rejeita faixa,
UTF-8/controles, BCD inválido, estado/valor incompatíveis, horário negativo,
horário sem leitura e contagem/stage incoerentes. Chaves extras não são aceitas.
Códigos GATT numéricos já não eram retidos por ReadViewModel: este snapshot
preserva a categoria gatt_error, sem inventar o status perdido.

Saída mantém version/id e result.snapshot, com delivery=cached_snapshot,
caller_asserted_origin=true e received_count derivado dos quatro campos.
Replay de um snapshot our_app continua cached_snapshot, sem nova prova física.
Erro semântico retorna invalid_snapshot preservando ID; erro de envelope segue
as regras existentes. A serialização Java recusa surrogates inválidos, estados
em andamento e ID negativo (Java long até 2^63−1; Rust aceita u64).
Arrays de saída pertencem ao chamador, sem cache global ou referência JNI retida.

## Reprodução offline

Fixture estritamente sintética, inclusive seu horário:

```sh
# Execute na raiz do projeto
cargo run --offline -p casca-cli -- --gatt-json < fixtures/basic-snapshot-synthetic.json
cargo test --offline
cargo fmt --all -- --check
cargo clippy --offline --all-targets -- -D warnings
sh android-bridge/test.sh
```

`--gatt-json` aceita os mesmos envelopes plan/interpret/snapshot da fronteira
existente e retorna uma resposta JSON após EOF; exit 0 para resultado e 1 para
erro. Não é REPL persistente, JSON-RPC/MCP ou canal conectado ao celular.
O modo --json antigo e suas capabilities continuam inalterados; não anunciam
uma leitura remota implementada. Não colar identificadores ou dados pessoais
em comandos ou logs compartilhados.

## Validação e gates

TDD: dois testes Rust falharam antes da operação/CLI; o harness Java não compilou
antes da API BasicSnapshot. Após implementação: PASS 58 testes Rust (4 novos),
fmt, clippy -D warnings; run_host.sh com JNI real, validação de memória JVM,
regressões SQLite e igualdade byte a byte com resposta do terminal; worker host.
PASS compileDebugJavaWithJavac com SDK já instalado, offline, sem gerar APK.
Build nativo Android validado separadamente; consultar validation.md.
Fixtures não contêm dados pessoais. Nenhum novo acesso ao aparelho nesta rodada.

Ainda NOT_RUN: este método em ART e comparação de seu resultado com a leitura
real na tela; transporte Android → cliente MCP externo. O teste físico anterior
por captura confirma apenas a leitura básica da 0.3, que não contém esta API.
O APK0.4 agora oferece Ver snapshot local após finalizar uma leitura. O botão
chama a mesma ponte JNI e mostra o envelope validado, sem outra leitura BLE
ou envio remoto. Compare os campos com a tela no teste físico ainda pendente.

Futuro transporte deve ter escopo de acesso escolhido, correlação, limites e
consentimento, sem endpoint aberto ou upload automático. Autenticação Huami,
credenciais, dados de saúde, gestão de apps e firmware seguem gates separados.
