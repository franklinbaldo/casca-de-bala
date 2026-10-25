# Casca de Bala · laboratório0.4

Repositório: https://github.com/franklinbaldo/casca-de-bala .
Esta fonte está preparada para esse destino; a publicação é feita separadamente.
No APK0.4 o nome exibido ainda é Minha pulseira — laboratório. Mantivemos
o binário já validado, sem reconstruí-lo apenas para trocar o nome.
O packageId org.workspace.band7.basicreader e os contratos permanecem iguais.

Instalador separado: **band7-basic-reader-0.4-lab-arm64.apk**, Android12+ ARM64.
Fonte correspondente: **band7-basic-reader-0.4-lab-source.tar.gz** (não instalador).
O APK0.3 anterior permanece uma alternativa estável sem radar.

Aplicativo Android pessoal para **Xiaomi Smart Band 7 comum**, com núcleo Rust e
console local. Licença do projeto: **AGPL-3.0-or-later**, com avisos de terceiros
preservados em [PROVENANCE.md](PROVENANCE.md) e [third_party](third_party/).
Este pacote contém a fonte correspondente ao APK0.4; não inclui APK, SDK, chaves ou dados pessoais.

## O que funciona e qual é a evidência

- Android foreground: selecionar dispositivo já vinculado ou buscar anúncios BLE,
  conectar explicitamente e ler bateria, revisão de hardware/software e PnP.
  Sem MAC manual obrigatório, pareamento automático, escrita GATT ou notificações.
  Capturas de tela são permitidas. Um teste relatado por captura de usuário da
  versão 0.3 mostrou identificação e bateria; não é teste de todos os aparelhos.
- Rust/CLI: contratos tipados, validação, mensagens correlacionadas e operações
  futuras retornando unsupported. `capabilities` e `validate-card` são offline.
- Snapshot básico: Android → Java/JNI → Rust e replay pelo terminal, preservando
  horário, origem declarada e leituras parciais. Testado com fixtures sintéticas
  no host e compilado para Android; a API nova ainda não foi exercitada em ART.
  Não existe transporte remoto Android→MCP implementado.
- Históricos Activity8/HR6/SpO2, SQLite, fragmentação Huami, filas e retomada:
  componentes executáveis offline com testes sintéticos, sem coleta física.
- Miniapp JS/API1, empacotamento e B7VM: experimentais, sem instalação física.
  O candidato histórico permanece installable:false; Product ID não define
  automaticamente target/deviceSource.

O [radar básico](docs/radar.md) compara RSSI de conexão em paradas marcadas
explicitamente; é código Android real com testes host, ainda sem teste físico.
Mapa GPS/deslocamento, autenticação Huami, coleta completa
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
