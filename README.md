# Casca de Bala · laboratório0.5

Repositório: https://github.com/franklinbaldo/casca-de-bala .
A versão 0.4 permanece preservada. Esta versão 0.5 acrescenta busca contínua e feedback visual de RSSI.
O packageId org.workspace.band7.basicreader e os contratos permanecem iguais.

Instalador separado: **casca-de-bala-0.5-lab-arm64.apk**, Android12+ ARM64.
Fonte correspondente: **band7-basic-reader-0.5-lab-source.tar.gz** (não instalador).
O APK0.3 anterior permanece uma alternativa estável sem radar.

Aplicativo Android pessoal para **Xiaomi Smart Band 7 comum**, com núcleo Rust e
console local. Licença do projeto: **AGPL-3.0-or-later**, com avisos de terceiros
preservados em [PROVENANCE.md](PROVENANCE.md) e [third_party](third_party/).
Este pacote contém a fonte correspondente ao APK0.5; não inclui APK, SDK, chaves ou dados pessoais.

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
Mapa GPS/deslocamento, autenticação Huami, coleta completa
de saúde, comunicação independente com miniapp, gestão real de aplicativos e
firmware próprio permanecem planejados ou em investigação. Não há Bash na Band,
root, daemon, endpoint aberto ou upload automático de histórico de saúde.
Objetivo: MCP → console no Android/núcleo Rust → BLE → Band, com eventos de volta.

## Compilar e testar

Com [just](https://just.systems), Rust (o `rust-toolchain.toml` fixa a versão),
Java 21, Node e um compilador C:

```sh
just test      # Rust, ponte JNI, miniapp e contratos de CI; sem Android SDK
just lint      # rustfmt, clippy e guarda de arquivos versionados
just apk       # APK release unsigned (ANDROID_HOME e ANDROID_NDK_HOME)
just install   # APK debug no celular conectado via adb
just ci        # exatamente o que o CI roda
cargo run --offline -- --gatt-json < tests/fixtures/basic-snapshot-synthetic.json
```

O Gradle vem pelo wrapper (`android-app/gradlew`, distribuição fixada por SHA-256).
Veja [BUILDING.md](BUILDING.md) para versões exatas, caches, Android e licença SDK.

Offline requer dependências já baixadas. Preparação de caches é explícita;
nenhum script aceita contratos ou instala SDK automaticamente. O exemplo de
snapshot é sintético e não se conecta ao celular ou ao relógio.

## Organização

`src/` e `tests/`: um crate Rust, biblioteca cdylib e CLI. `android-app/`: aplicativo
Android e build Gradle. `android-bridge/`: módulo Gradle `:bridge` (Java puro),
shim JNI e harnesses de host (`test.sh`). `ffi/`: C ABI.
`miniapp/` e `bench/`: experimentos existentes. `docs/`: contratos, evidências,
limites e plano de recuperação. [Validação desta fonte](docs/validation.md).
Não há diretórios vazios para funcionalidades futuras.

Mudanças de firmware ou software no relógio exigem identificação, compatibilidade,
recuperação e autorização separadas. A aplicação básica não concede acesso total.
Este projeto não é afiliado nem certificado pela Xiaomi, Zepp ou Bluetooth SIG.
