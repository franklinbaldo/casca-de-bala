# CI Android e releases

Estado: build, assinatura permanente e publicação da base 0.5 passaram na main
8c0f9b5502d14153544c29b30b41c81050319e52, execução
[40381344254](https://github.com/franklinbaldo/casca-de-bala/actions/runs/40381344254).
Esta proposta integra a fonte 0.6 sem alterar os guards de secrets ou o certificado.
O novo piloto ainda não foi autenticado no hardware.

## Fluxo

- PR: runner padrão `ubuntu-24.04`, token somente leitura, nenhum secret,
  teste Rust/JVM/JNI/miniapp, lint e duas gerações do APK ARM64 **unsigned**.
- Push na main, inclusive merge: mesmo build, fonte AGPL correspondente,
  SHA256SUMS e rascunho de release. Cada commit de main mantém seu run.
- Com a flag CASCA_ACTIONS_SIGNING_READY desabilitada, main prepara somente um
  rascunho sem instalador. O unsigned da PR não pode ser instalado e expira após
  14 dias. Não é apresentado como APK de teste nem como versão 0.6.
- Com a flag habilitada, main assina com a chave existente do ambiente
  android-release e exige o certificado fixado em release-config.json. O job
  publicador verifica novamente o APK, baixa e compara assets antes de publicar
  a prerelease. Não gera chaves, substitui assets divergentes ou remove releases.

`versionCode = 1000 + git rev-list --first-parent --count HEAD`; versão/tag incluem
SHA. Main sem reescrita cresce monotonicamente; reexecutar o mesmo commit mantém
versão e não altera release/arquivos existentes. Histórico raso é recusado.
Não remover/recriar o histórico main: isso quebra a regra. Jobs de commits mais
antigos podem terminar depois; os rascunhos nunca são marcados como latest.

## Ferramentas e dependências

Rust 1.99.1 + componentes/target fixados; Gradle 8.11.1; AGP 8.9.2; SDK 35,
build-tools 35.0.0 e NDK 27.0.12077973. JDK 21 Temurin e imagem Ubuntu recebem
atualizações: este não é um ambiente hermético nem promessa de bytes idênticos
entre imagens futuras. A repetibilidade é verificada dentro de cada run.
Actions fixadas por SHA; caches Gradle desativados. Não há keytool no fluxo CI.

`CI_PREPARE_NETWORK=1` permite fetch Cargo locked e resolução Gradle estrita;
jamais escreve verification-metadata.xml. Compilação repetida é offline. Novos
artefatos/checksums ausentes falham e exigem revisão separada. O sdkmanager usa
licença já presente na imagem; stdin fechado, sem `yes` nem `--licenses`.
Não há concessão automática de acesso de hardware ou aprovação de novos termos.
O sdkmanager é chamado por caminho absoluto dentro do SDK: a imagem do runner
pode fornecer a ferramenta sem incluí-la no PATH. Testes sintéticos cobrem esse
caso, caminhos com espaços, stdin fechado e pré-requisitos/instalação falhando.

O runner sem cache encontrou dois metadados `junit-bom` já usados pelo plugin,
ausentes da lista inicial. A manutenção explícita registra apenas esses dois
hashes em [dependency-review.json](dependency-review.json): assinaturas PGP
verificadas com a chave pública indicada por junit.org e constraints comparadas
integralmente aos POMs cujos hashes já estavam fixados. Nenhuma versão ou binário
foi atualizado; não se usou `--write-verification-metadata` nem modo permissivo.

Reprodução com ferramentas/caches já existentes, raiz de checkout completo:

```sh
python3 -m unittest discover -s ci -p 'test_*.py' -v
export GRADLE_BIN=/caminho/gradle-8.11.1/bin/gradle
export ANDROID_HOME=/caminho/sdk
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.0.12077973"
# Em sandbox com HOME somente leitura, usar também ANDROID_USER_HOME gravável.
sh ci/build.sh
```

## Assinatura automática aprovada

A chave permanente e a senha existentes foram transferidas, com autorização,
para os Actions Secrets do ambiente android-release, restrito à branch main.
Os nomes são CASCA_ANDROID_KEYSTORE_B64 e CASCA_ANDROID_KEYSTORE_PASSWORD.
Não existem secrets de assinatura no escopo geral do repositório. A custódia e
o backup originais no Google Cloud foram preservados; nenhuma chave foi gerada.
O workflow não precisa de acesso ao Jatobá, Google Cloud, IAM ou OIDC.

Somente o passo de assinatura recebe os valores. Ele os remove do ambiente antes
dos subprocessos, usa arquivos temporários privados fora dos artefatos e os limpa
no finally. Não passa senhas em argumentos nem publica keystore. Não se promete
apagamento absoluto da memória do Python/JVM/runner. O certificado esperado é
público e fixado no código; não aceitar automaticamente um certificado diferente.

Assinatura e publicação aceitam somente push na main, com commit e hashes do
mesmo run. Além do guard do workflow e do script, a regra real do ambiente impede
acesso aos secrets por refs de PR. PRs produzem apenas artefatos unsigned.
O publicador recebe GH_TOKEN somente após verificação independente do APK.
A fonte AGPL, hashes e recibo acompanham o instalador. Atestações Sigstore não
foram implementadas e não substituem assinatura APK.

A primeira migração de chaves temporárias diferentes pode exigir remover somente
o Casca anterior; preservar Zepp Life e pareamento. A assinatura é do projeto,
não uma certificação Google Play. Os testes de signer/publicador usam ferramentas
simuladas e valores sintéticos; a assinatura real da base 0.5 foi verificada na execução acima. Uma release 0.6
ainda depende do merge desta proposta e de sua própria execução bem-sucedida.

GitHub fornece gratuitamente runners **padrão** em repositórios públicos;
runners maiores são cobrados. Não é autorização de infraestrutura paga.
Referências: https://docs.github.com/en/actions/concepts/billing-and-usage e
https://docs.github.com/en/actions/reference/security/secure-use .

Testes host/build não demonstram ART, BLE ou funcionamento físico. Registros de
validação da PR devem separar PASS, FAIL e NOT_RUN sem reaproveitar contagens
de outra revisão.

## Integração local 0.6

O build inclui os harnesses de autenticação, criptografia e referência upstream,
além dos testes anteriores. BC 1.86 é aceito somente com o hash já revisado; o
fetch oficial ocorre apenas com CI_PREPARE_NETWORK=1. Offline, definir BC_JAR
para o artefato já validado. O mirror Maven local opcional usa
ORG_GRADLE_PROJECT_band7BcRepository e mantém a verificação estrita.

Fixtures Android e testes criptográficos entram somente no perfil isolado de
emulador. A validação do release usa apkanalyzer dex packages --defined-only para
verificar a presença do piloto e ausência de fixtures/referências de teste.
APKANALYZER permite indicar o executável oficial existente fora do SDK padrão.
Testes ART históricos não são atribuídos automaticamente ao novo checkout integrado.
A PR valida host/build; autenticação física permanece NOT_RUN.
