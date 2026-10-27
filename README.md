# Casca de Bala

Repositório: https://github.com/franklinbaldo/casca-de-bala .

O app **Casca** é o [Gadgetbridge](https://gadgetbridge.org) com um **hub MCP**: as
pulseiras e relógios que o Gadgetbridge suporta (Xiaomi Smart Band 7 incluída)
ficam acessíveis às suas IAs pelo celular, com aprovação do dono para cada uma.
O Gadgetbridge entra como upstream fixado ([gb/](gb/)): um patch mínimo cria o
flavor `casca`, e o overlay traz o servidor MCP e o atalho "Casca MCP". O núcleo
MCP é Rust ([crates/casca-mcp](crates/casca-mcp)). Pacote
`org.workspace.band7.basicreader`, o mesmo das versões anteriores: atualiza no lugar.

Licença: **AGPL-3.0-or-later**, a mesma do Gadgetbridge, com avisos de terceiros
em [PROVENANCE.md](PROVENANCE.md) e [third_party](third_party/). O app de
laboratório anterior (leitor/radar BLE próprio, `android-app/`) continua no
repositório, fora do APK publicado.

## Instalar

Cada push na `main` publica um APK ARM64 assinado como prerelease
`ci-v<versionCode>-<commit>` nas [Releases](https://github.com/franklinbaldo/casca-de-bala/releases).
Android 12+, ARM64. Duas formas de receber atualizações automáticas:

- **Obtainium:** adicione `https://github.com/franklinbaldo/casca-de-bala` e
  ative "Include prereleases".
- **F-Droid:** adicione o repositório do projeto, publicado em
  https://franklinbaldo.github.io/casca-de-bala/ (link com a impressão digital
  do repositório). Ele traz as últimas 5 releases, conferidas contra o
  certificado fixado do APK.

Quem tinha uma versão de laboratório com outra chave pode precisar remover
somente o Casca anterior uma vez; preserve Zepp Life e o pareamento.

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
root ou upload automático de histórico de saúde.

O Casca é o hub entre os seus dispositivos e as suas IAs: o próprio celular
serve [MCP](docs/mcp.md). O servidor só existe quando você o liga no app,
aceita apenas loopback e a tailnet, e cada IA precisa ser aprovada no celular
com um código. Hoje ele entrega a última leitura da pulseira; o carro (Android
Auto) e outros dispositivos entram como novos adaptadores do mesmo contrato.

## Compilar e testar

Com [just](https://just.systems), Rust (o `rust-toolchain.toml` fixa a versão),
Java 21, Node e um compilador C:

```sh
just test      # Rust, ponte JNI, miniapp e contratos de CI; sem Android SDK
just lint      # rustfmt, clippy e guarda de arquivos versionados
just apk       # APK release unsigned (ANDROID_HOME e ANDROID_NDK_HOME)
just install   # APK debug no celular conectado via adb
just ci        # exatamente o que o CI roda
cargo run --offline -p casca-cli -- --gatt-json < fixtures/basic-snapshot-synthetic.json
```

O Gradle vem pelo wrapper (`android-app/gradlew`, distribuição fixada por SHA-256).
Veja [BUILDING.md](BUILDING.md) para versões exatas, caches, Android e licença SDK.

Offline requer dependências já baixadas. Preparação de caches é explícita;
nenhum script aceita contratos ou instala SDK automaticamente. O exemplo de
snapshot é sintético e não se conecta ao celular ou ao relógio.

## Organização

Workspace Cargo em `crates/`, dependências apontando só para baixo:

| Crate | Papel | No APK |
| --- | --- | --- |
| `casca-core` | contratos tipados, decoders GATT/Huami/Activity8/HR/SpO2; sem I/O | sim |
| `casca-store` | SQLite (rusqlite bundled) e SyncSession sobre o core | sim |
| `casca-ffi` | C ABI (`ffi/band7.h`), gera `libband7_terminal.so` | sim |
| `casca-cli` | console offline `band7-terminal` | não |
| `casca-mcp` | servidor MCP do celular (HTTP, pareamento, tokens, ferramentas de laboratório) | sim |
| `casca-ble` | laboratório BLE em Rust (btleplug + JNI), só no Android | sim |
| `casca-microvm` | B7VM v1 experimental | não |

`fixtures/`: entradas sintéticas/sanitizadas compartilhadas. `android-app/`: aplicativo
Android e build Gradle. `android-bridge/`: módulo Gradle `:bridge` (Java puro),
shim JNI e harnesses de host (`test.sh`). `ffi/`: C ABI.
`miniapp/` e `bench/`: experimentos existentes. `docs/`: contratos, evidências,
limites e plano de recuperação. [Validação desta fonte](docs/validation.md).
Não há diretórios vazios para funcionalidades futuras.

Mudanças de firmware ou software no relógio exigem identificação, compatibilidade,
recuperação e autorização separadas. A aplicação básica não concede acesso total.
Este projeto não é afiliado nem certificado pela Xiaomi, Zepp ou Bluetooth SIG.
