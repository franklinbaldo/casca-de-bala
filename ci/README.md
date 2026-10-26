# CI Android e releases

Estado: implementação proposta em PR; execução GitHub e publicação automática
do APK assinado ainda não comprovadas. A fonte pública atual é 0.5; esta mudança
não importa silenciosamente funcionalidades da preparação local 0.6.
Inclui somente a correção do ícone próprio já existente nessa preparação:
o ícone do framework usado na0.5 impedia `aapt dump badging` de validar o APK.

## Fluxo

- PR: runner padrão `ubuntu-24.04`, token somente leitura, nenhum secret,
  teste Rust/JVM/JNI/miniapp, lint e duas gerações do APK ARM64 **unsigned**.
- Push na main, inclusive merge: mesmo build, fonte AGPL correspondente,
  SHA256SUMS e rascunho de release. Cada commit de main mantém seu run.
- O rascunho não publica instalador. O APK unsigned fica no artefato do run,
  expira após 14 dias e não pode ser instalado. A fonte é anexada ao rascunho.
- Uma assinatura permanente verificada e a publicação do rascunho são o gate
  pendente. Não gerar outra chave a cada run nem anunciar atualização compatível.

`versionCode = 1000 + git rev-list --first-parent --count HEAD`; versão/tag incluem
SHA. Main sem reescrita cresce monotonicamente; reexecutar o mesmo commit mantém
versão e não altera release/arquivos existentes. Histórico raso é recusado.
Não remover/recriar o histórico main: isso quebra a regra. Jobs de commits mais
antigos podem terminar depois; os rascunhos nunca são marcados como latest.

## Ferramentas e dependências

Rust1.99.1 + componentes/target fixados; Gradle8.11.1; AGP8.9.2; SDK35,
build-tools35.0.0 e NDK27.0.12077973. JDK21 Temurin e imagem Ubuntu recebem
atualizações: este não é um ambiente hermético nem promessa de bytes idênticos
entre imagens futuras. A repetibilidade é verificada dentro de cada run.
Actions fixadas por SHA; caches Gradle desativados. Não há keytool no fluxo CI.

`CI_PREPARE_NETWORK=1` permite fetch Cargo locked e resolução Gradle estrita;
jamais escreve verification-metadata.xml. Compilação repetida é offline. Novos
artefatos/checksums ausentes falham e exigem revisão separada. O sdkmanager usa
licença já presente na imagem; stdin fechado, sem `yes` nem `--licenses`.
Não há concessão automática de acesso de hardware ou aprovação de novos termos.

Reprodução com ferramentas/caches já existentes, raiz de checkout completo:

```sh
python3 -m unittest discover -s ci -p 'test_*.py' -v
export GRADLE_BIN=/caminho/gradle-8.11.1/bin/gradle
export ANDROID_HOME=/caminho/sdk
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.0.12077973"
# Em sandbox com HOME somente leitura, usar também ANDROID_USER_HOME gravável.
sh ci/build.sh
```

## Assinatura pendente: manter Google Cloud + Jatobá

A chave permanente existente permanece no Secret Manager. Este workflow não
lê/copia a chave, não cria secrets GitHub, OIDC, IAM, endpoint ou credencial.
Para publicar automaticamente instaladores, falta um transporte autenticado que
entregue o unsigned e seu hash ao Jatobá e retorne o APK assinado com certificado
esperado. Isso exige decisão específica de identidade/acesso; não está instalado.
Só depois ligar verificação de certificado/package/version/ABI/alinhamento,
anexar APK assinado e fonte/hash à release e publicar o rascunho. Nunca publicar
o unsigned como substituto ou usar segredo em jobs de PR.

GitHub fornece gratuitamente runners **padrão** em repositórios públicos;
runners maiores são cobrados. Não é autorização de infraestrutura paga.
Referências: https://docs.github.com/en/actions/concepts/billing-and-usage e
https://docs.github.com/en/actions/reference/security/secure-use .

Testes host/build não demonstram ART, BLE ou funcionamento físico. Registros de
validação da PR devem separar PASS, FAIL e NOT_RUN sem reaproveitar contagens
de outra revisão.
