# CI Android e releases

Estado: build de PR validado no GitHub; assinatura real e publicação aguardam
o primeiro push aprovado na main. A fonte pública atual é 0.5; esta mudança
não importa silenciosamente funcionalidades da preparação local 0.6.
Inclui somente a correção do ícone próprio já existente nessa preparação:
o ícone do framework usado na0.5 impedia `aapt dump badging` de validar o APK.

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

Rust 1.99.1 + componentes/target fixados em rust-toolchain.toml; Gradle 8.11.1 pelo
wrapper, com SHA-256 da distribuição e validação do JAR pelo setup-gradle; AGP 8.9.2; SDK 35,
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
simuladas e valores sintéticos; a assinatura real com a chave permanente só pode
ser comprovada após o primeiro merge autorizado e push na main.

GitHub fornece gratuitamente runners **padrão** em repositórios públicos;
runners maiores são cobrados. Não é autorização de infraestrutura paga.
Referências: https://docs.github.com/en/actions/concepts/billing-and-usage e
https://docs.github.com/en/actions/reference/security/secure-use .

Testes host/build não demonstram ART, BLE ou funcionamento físico. Registros de
validação da PR devem separar PASS, FAIL e NOT_RUN sem reaproveitar contagens
de outra revisão.

## Repositório F-Droid próprio

Depois do job `release`, `fdroid` reconstrói o repositório do zero a partir
das 5 releases `ci-v*` publicadas mais recentes: baixa cada APK, exige o
certificado fixado em release-config.json (o mesmo `check_certificate` do
publicador) e roda `fdroid update`, que assina **somente o índice** com a chave
do repositório. O job `pages` publica o resultado no GitHub Pages:
https://franklinbaldo.github.io/casca-de-bala/fdroid/repo . Não há estado
entre execuções; perder o site não perde nada que as releases não refaçam.

fdroidserver 2.4.5 e suas dependências (Pillow, androguard, entre outras) são
ferramenta de CI, instaladas com `--require-hashes` de
[fdroid-requirements.txt](fdroid-requirements.txt) (uv pip compile, Python 3.12).
Não entram no APK. Metadados e ícone do app: [fdroid/metadata](../fdroid/metadata).

Ativação única, em máquina confiável:

1. `sh ci/fdroid-repo-key.sh /diretorio/privado`: gera a chave RSA 4096 do
   índice (PKCS12) e a senha. Não é a chave do APK. Perdê-la obriga a
   readicionar o repositório no celular, não a reinstalar o app. Guardar backup
   junto à custódia da chave do APK.
2. No ambiente `android-release`: secrets `CASCA_FDROID_KEYSTORE_B64` e
   `CASCA_FDROID_KEYSTORE_PASSWORD`.
3. Variável de repositório `CASCA_FDROID_READY=true` (nível de repositório,
   como `CASCA_ACTIONS_SIGNING_READY`, porque o `if:` do job a lê antes de
   entrar no ambiente).
4. Settings → Pages → Source: **GitHub Actions**.

O próximo push na `main` publica o repositório; o resumo do job mostra o link
com a impressão digital (`?fingerprint=`), que o F-Droid usa para fixar a chave
do repositório na primeira adição.
