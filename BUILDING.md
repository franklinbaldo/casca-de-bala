# Casca de Bala — build fora do monorepo

Execute a partir da raiz que contém Cargo.toml; não é necessário copiar o
workspace original, seu histórico, contas, fotos ou dados de dispositivo.

## Ferramentas de referência

| Componente | Versão |
| --- | --- |
| Rust/rustc + std Android | 1.99.1; target aarch64-linux-android (rust-toolchain.toml) |
| Cargo, rustfmt, clippy | mesma distribuição Rust |
| Java | 21; referência 21.0.12.1, com jdk.compiler e jdk.jlink |
| Gradle | 8.11.1, via `android-app/gradlew` (SHA-256 da distribuição fixado) |
| Android Gradle Plugin | 8.9.2 |
| SDK Platform | android-35, revisão 2 |
| SDK Build Tools | 35.0.0 |
| Android NDK | 27.0.12077973 |
| Command-line Tools SDK | 19.0 |
| C host | compilador C11; harness JNI subset validado em Linux x86_64 |
| Python | 3.12+ stdlib (inspetor miniapp e contratos de CI) |

Node executa testes node:test e auditoria de fonte, sem dependências npm.
Versão usada nesta exportação: Node24.19.0; scripts usam import.meta.dirname
(Node20.11+). O recibo de validação registra a execução. Rust/Cargo e Gradle
usam lockfiles/checksums existentes. Não são distribuídos caches, SDKs ou headers
copiados de ferramentas; o ramo Android usa jni.h oficial do NDK.

## Host

Configure PATH para Rust, Java e cc já instalados. Com cache vazio, prepare
explicitamente `cargo fetch --locked` em ambiente de rede autorizado. Depois:

```sh
just test
just lint
node bench/size.mjs
```

`android-bridge/test.sh` compila e executa toda classe em `android-bridge/tests/`:
basta criar o arquivo para o harness entrar no CI.

Harnesses usam JVM host com -Xcheck:jni e SQLite real com dados sintéticos.
Não são testes ART/radio/Band. Testes de dados não precisam de conta ou chave Huami.

## Android: compilar sem APK ou assinatura

Instale as ferramentas oficiais em seu ambiente, aceitando pessoalmente os
contratos aplicáveis do SDK: https://developer.android.com/studio/terms .
A aceitação de outra pessoa/ambiente não é distribuída neste pacote.
Não execute sdkmanager --licenses silenciosamente. Configure os caminhos:

```sh
export JAVA_HOME=/caminho/jdk21
export ANDROID_HOME=/caminho/android-sdk
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.0.12077973"
sh android-app/build-native.sh
cd android-app
./gradlew --offline --no-daemon --dependency-verification strict :app:compileDebugJavaWithJavac :app:lintDebug
```

`ANDROID_USER_HOME` e `GRADLE_USER_HOME` podem apontar para diretórios locais
privados próprios. Dependências Maven precisam ter sido resolvidas anteriormente;
remova --offline somente para preparação consciente de rede/cache. Repositórios
estão declarados em settings.gradle; verification-metadata.xml preserva hashes.
Não usamos CI remoto, submódulos ou serviços cloud para este build.

O script prepare-local-java.sh só se aplica a imagens Java reduzidas: exige
JAVA_SOURCE_HOME e JAVA_COPY_HOME explícitos e copia módulos existentes, sem
instalar ou alterar o JDK original. JDK21 completo não precisa desse workaround.

## APK laboratorial0.5

Nenhum APK é distribuído com esta fonte. O script build-apk.sh contém a receita
laboratorial preexistente: gera chave debug temporária, testa e apaga a chave.
Foi executado para o APK0.5 com chave temporária removida ao terminar.
As duas gerações com essa mesma chave foram idênticas. Recompilar com outra
chave muda o certificado e pode impedir atualização no lugar. Para uma release futura, decidir identidade
de assinatura e gerar APK somente junto com fonte correspondente validada.
Não usar chave de produção no repositório, nem confundir assinatura APK com
credencial Bluetooth/Huami. Não instalar, parear ou escrever na Band pelo build.

## Miniapp opcional

Veja miniapp/packaging/README.md: inspetor offline em Python stdlib, sem
dependências externas. O builder ZMake de laboratório saiu da árvore e continua
disponível no histórico Git. Fonte própria do miniapp está incluída. Pacote
instalável e compatibilidade física não são satisfeitos pelos testes com mocks.

## Verificação de dependências e manutenção consciente

O build normal build-apk.sh exige verification-metadata.xml, usa modo estrito
em suas três chamadas Gradle e é offline. A configuração/plugin é verificada
antes de gerar qualquer chave de laboratório. Checksum ausente/divergente falha;
o build não escreve metadados nem confia em downloads novos silenciosamente.

Alterar pins/checksums é manutenção separada: revisar coordenadas/versões,
verificar origem e integridade por canais independentes, guardar cópia do XML e
executar conscientemente a atualização somente para tarefas escolhidas. Exemplo
para dependências de compilação/lint, sem APK ou keytool, em android-app/:

```sh
cp gradle/verification-metadata.xml /caminho/metadata-anterior.xml
./gradlew --no-daemon --write-verification-metadata sha256 :app:compileDebugJavaWithJavac :app:lintDebug
diff -u /caminho/metadata-anterior.xml gradle/verification-metadata.xml
```

Essa atualização pode acessar os repositórios configurados. Hash gerado do
próprio download não é validação independente. Não aceitar o diff automaticamente:
confirmar cada artefato, preservar avisos/pins e só então versionar o XML revisado.
Uma falha de verificação no build normal não autoriza executar essa manutenção.
APK futuro pode exigir artefatos adicionais: resolver/revisar conscientemente,
sem remover a verificação estrita. Esta exportação não executou a atualização.

Regressão real, com Gradle/cache/SDK já provisionados, a partir da raiz:

```sh
node scripts/test-gradle-verification.mjs
```

O teste copia a configuração Android para diretório temporário e executa Gradle
help estrito/offline em três casos: metadata original, checksum AGP divergente e
checksum AGP ausente. Não executa build-apk.sh, assemble ou keytool; comprova falha
sem escrever o XML e sem rede. Logs de paths temporários não são distribuídos.

Na entrega0.5, Rust/C/receita nativa são idênticos à0.4 (18 arquivos de entrada
comparados byte a byte). Bibliotecas arm64 foram reutilizadas dessa compilação
NDK27 validada; o build nativo não foi reexecutado na0.5. A receita acima permite
recriá-las a partir desta fonte. Java Android/UI foram recompilados.

## Loop curto de Rust (Casca)

Após preparar dependências com `cargo fetch --locked`, execute `bacon clippy`
(`--all-targets`, `--offline`, `-D warnings`) durante edições, `bacon test`
para o conjunto Rust e `bacon fmt` para conferir a formatação. `bacon` é
instalado separadamente (`cargo install --locked bacon`); não integra o APK.
O CI continua executando o gate de testes, JNI/Java, roteamento e APK real.

Os testes de integração do Casca ficam agrupados por **crate**, nunca num
único executável para todo o workspace: `casca-cli/tests/cli/main.rs` cobre
a CLI, `casca-core/tests/contracts/main.rs` verifica a API pública e
`casca-store/tests/storage/main.rs` verifica SQLite e transações. Testes
puramente sintéticos dos decodificadores estão em `src/*/tests.rs`, sob
`#[cfg(test)]`. FFI e MCP/HTTP conservam seus executáveis de integração
porque verificam interfaces efetivas.

Antes/depois de reorganizar os testes, usar a mesma toolchain e executor
com caches frios equivalentes, registrando tempo e número de executáveis:

```text
cargo build --locked --timings
cargo test --locked --no-run --timings
cargo test --locked
```

Os relatórios ficam em `target/cargo-timings/`. O `cargo build` sozinho não
mede a linkedição dos executáveis de teste; para isso use `test --no-run`.
Resultados de cache quente não são comparáveis diretamente aos de cache frio.

O `ci/test_rust_test_layout.py` garante no CI no máximo um alvo de
integração por crate; novos cenários devem entrar nos módulos existentes.
