# Casca de Bala · piloto 0.6

Aplicativo Android para **Xiaomi Smart Band 7 comum**, com núcleo Rust e console
local. Licença **AGPL-3.0-or-later**; avisos preservados em
[PROVENANCE.md](PROVENANCE.md) e [third_party](third_party/).
Repositório: https://github.com/franklinbaldo/casca-de-bala .

Esta proposta integra o piloto de sessão autenticada à base pública 0.5, mantendo
leitor básico, snapshot e radar. Depois de selecionar a pulseira e concluir a
leitura básica, a ação **Piloto da sessão · acesso avançado** permite entrada local
explícita de credencial legítima e, após AUTH, uma consulta separada de bateria.
Não obtém credenciais, não consulta automaticamente e não coleta saúde.
**Autenticação e bateria proprietária na Band real: NOT_RUN.** Não envie chave
pelo chat. Veja [o roteiro e os limites](docs/product-pilot-0.6.md).

PRs geram APK ARM64 **unsigned, não instalável**, além da fonte correspondente.
Após merge aprovado, o CI da main testa, assina com o certificado permanente e
publica APK, fonte AGPL, hashes e recibo na
[página de releases](https://github.com/franklinbaldo/casca-de-bala/releases).
A numeração CI continua acima de 1003; o packageId permanece
`org.workspace.band7.basicreader`. Esta PR não altera secrets nem certificado.
A release automática anterior usa a base 0.5; não contém este piloto.

## O que funciona e qual é a evidência

- Android foreground: selecionar dispositivo já vinculado ou buscar anúncios BLE,
  conectar explicitamente e ler bateria, revisão de hardware/software e PnP.
  Sem MAC manual obrigatório ou pareamento automático. Esse leitor básico não
  escreve GATT; o piloto separado usa escritas de protocolo e subscrição RX/ACK.
  Capturas de tela são permitidas. Um teste relatado por captura de usuário da
  versão 0.3 mostrou identificação e bateria; não é teste de todos os aparelhos.
- Rust/CLI: contratos tipados, validação, mensagens correlacionadas e operações
  futuras retornando unsupported. `capabilities` e `validate-card` são offline.
- Snapshot básico: Android → Java/JNI → Rust e replay pelo terminal, preservando
  horário, origem declarada e leituras parciais. Testado com fixtures sintéticas
  no host. Na versão 0.4 o usuário relatou bateria 74% igual na tela e no diálogo
  validado pelo Rust, sem erro. Somente bateria foi comparada explicitamente.
  Esse relato não confirma cada revisão/horário, rádio RSSI ou transporte remoto.
  Não existe transporte remoto Android→MCP implementado.
- Históricos Activity8/HR6/SpO2, SQLite, fragmentação Huami, filas e retomada:
  componentes executáveis offline com testes sintéticos, sem coleta física.
- Miniapp JS/API1, empacotamento e B7VM: experimentais, sem instalação física.
  O candidato histórico permanece installable:false; Product ID não define
  automaticamente target/deviceSource.

O [radar visual contínuo](docs/radar.md) anima o recebimento de sinal válido,
mostra mediana recente, qualidade e tendência entre janelas automáticas.
Não exige clicar em cada parada, não desenha alvo em posição/ângulo fictício
e não estima distância. O histórico mostra ordem temporal, não mapa espacial.
O usuário relatou abrir o radar0.4 e medir paradas, mas não confirmou valores
RSSI; a coleta e a UX0.5 ainda exigem teste físico.
Mapa GPS/deslocamento, interoperabilidade física da autenticação Huami, coleta completa
de saúde, comunicação independente com miniapp, gestão real de aplicativos e
firmware próprio permanecem planejados ou em investigação. Não há Bash na Band,
root, daemon, endpoint aberto ou upload automático de histórico de saúde.
Objetivo: MCP → console no Android/núcleo Rust → BLE → Band, com eventos de volta.

## Compilar e testar

Veja [BUILDING.md](BUILDING.md) para versões exatas, caches, Android e licença SDK.
Execute na raiz extraída, sem depender do monorepo:

```sh
cargo test --offline
cargo fmt --all -- --check
cargo clippy --offline --all-targets -- -D warnings
sh android-bridge/run_host.sh
sh android-bridge/run_worker_host.sh
node --test miniapp/tests/page.test.mjs miniapp/tests/vm.test.mjs
cargo run --offline -- --gatt-json < tests/fixtures/basic-snapshot-synthetic.json
node scripts/check-source.mjs
```

Offline requer dependências já baixadas. Preparação de caches é explícita;
nenhum script aceita contratos ou instala SDK automaticamente. O exemplo de
snapshot é sintético e não se conecta ao celular ou ao relógio.

## Organização

`src/` e `tests/`: um crate Rust, biblioteca cdylib e CLI. `android-app/`: aplicativo
Android e build Gradle. `android-bridge/` e `ffi/`: Java/JNI/C ABI e harnesses.
`miniapp/` e `bench/`: experimentos existentes. `docs/`: contratos, evidências,
limites e plano de recuperação. [Validação desta fonte](docs/validation.md).
Não há diretórios vazios para funcionalidades futuras.

Mudanças de firmware ou software no relógio exigem identificação, compatibilidade,
recuperação e autorização separadas. A aplicação básica não concede acesso total.
Este projeto não é afiliado nem certificado pela Xiaomi, Zepp ou Bluetooth SIG.
