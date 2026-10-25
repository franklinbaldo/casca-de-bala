# Validação da fonte isolada — 25/10/2026

Esta cópia foi compilada fora do monorepo, sem histórico Git, submódulo ou código
local externo obrigatório para Rust/Android. Caches e toolchains existentes foram
reutilizados; nenhum dado de aparelho foi lido nem chave criada. Nome interno
Band7 mantido; não há repositório ou release binária criado por este pacote.

| Verificação executada nesta fonte | Resultado |
| --- | --- |
| cargo test --offline | PASS, 58 testes |
| cargo fmt --all -- --check | PASS |
| cargo clippy --offline --all-targets -- -D warnings | PASS |
| android-bridge/run_host.sh | PASS; 7 harnesses, incluindo radar sintético e snapshot Java/JNI/Rust/CLI |
| android-bridge/run_worker_host.sh | PASS, backend sintético do worker |
| Harness C ABI, cc -Wall -Wextra -Werror | PASS; decoder/SQLite/reabertura reais, fixture sintética |
| Node testes página e B7VM | PASS, 7 testes |
| Inspetor Python unittest via uv/Python3.12 | PASS, 5 testes |
| Nativo Android build-native.sh | PASS, arm64, NDK27.0.12077973 e jni.h oficial |
| Gradle compileDebugJavaWithJavac | PASS, offline, Java21/SDK35 |
| Verificação Gradle estrita/offline: original, divergente e ausente | PASS, 3 casos; XML inalterado, sem APK/keytool |
| Sintaxe shell build-apk.sh (sh -n) | PASS |
| Gradle lintDebug | PASS com aviso: 0 erros, 1 DataExtractionRules |
| ZMake fixado: duas gerações normalizadas | PASS, candidatos idênticos, installable:false |
| Mock runtime do candidato ZMake | PASS, 1 teste; não é API1 física |

Aviso lint: allowBackup=false sem dataExtractionRules no manifesto. Nenhuma
regra de backup ou acesso persistente foi ampliada nesta exportação. O build
Python inicialmente falhou em cache sem escrita; repetido com cache local
configurado, passou. Falhas remanescentes de testes executados: nenhuma.

Candidato miniapp opcional:4906 bytes, SHA256
096cfee07fd590f45c773457b13e1e278013ac6b9548d2cf93e5d541d9245f7e.
Ferramenta externa previamente instalada, commit47acece62528a86a1445d92414cd9a087037eb1c.
O candidato não integra este pacote fonte, e sua compatibilidade/instalação não
foram testadas. O formato contém payload de dispositivo gerado pelo builder,
não um ZIP genérico apresentado como instalável.

## NOT_RUN e limites

- APK0.4 foi compilado e assinado em laboratório; nenhuma instalação no celular ou Play Store.
- Sem ART/CheckJNI no Android, nova leitura física ou teste do snapshot novo no
  aparelho. A captura relatada da0.3 comprova apenas identificação/bateria exibidas
  naquela leitura, não cada rota de descoberta, todas as variantes ou transporte.
- Autenticação Huami, fetch real de saúde, canal Android↔miniapp, gestão real de
  apps, GPS/mapa espacial, MCP remoto e firmware próprio não foram validados/implementados
  como caminho físico desta release de fontes.
- SQLite sintético não prova cifra em repouso, recuperação de falta de energia ou
  proteção de dados reais. Esses gates continuam antes de qualquer histórico pessoal.

Manifesto/hash e verificação de conteúdo são limitados à lista de fontes
distribuídas; não são certificação geral de segurança ou SBOM de um APK final.
Copias de avisos permissivos e GPL/AGPL permanecem separadas da licença autoral.

## Correção da receita de build após revisão

O build padrão não usa geração de metadados: exige XML existente, verifica
plugins antes da assinatura e usa --offline --dependency-verification strict nas
três chamadas. Atualização de checksums é manutenção explícita em BUILDING.md.
O teste scripts/test-gradle-verification.mjs falhou antes da correção pela opção
de escrita automática. Depois passou com Gradle/AGP reais: XML original aceito,
checksum do JAR AGP divergente e ausente recusados. XMLs não foram reescritos.
Não houve rede, assemble, chave ou APK. Fontes/pins/avisos das aplicações não
foram alterados; checks Rust/JNI/Android acima foram preservados da validação
anterior dessas mesmas fontes. A frase histórica ZMake em evidence.md agora
distingue consulta inicial de execução posterior.

## APK0.4 e radar básico

Build antes da pausa e verificação final na retomada: versionCode4/0.4-lab,
2922303 bytes, SHA25671fe054cadd942a5a7438097931094e1a5f1911282921a25cc0136dcb820a0a9.
PASS58 testes Rust, fmt/clippy, 7 harnesses Java/JNI incluindo RadarSessionTest,
worker e7 testes Node. Radar e isFinal foram testados RED antes da implementação.
PASS assembleDebug repetido com bytes idênticos; lint0 erros/1 DataExtractionRules.
Primeiro lint acusou guarda CONNECT no RSSI: corrigida explicitamente, sem supressão.
PASS assinatura v2RSA2048 temporária, zipalign -P16, manifesto por apkanalyzer e
aapt2 xmltree, classes radar/snapshot em DEX;60 assets de licenças/proveniência
conferidos byte a byte. Nativos conferidos contra saída AGP após stripDebugSymbols
(a saída do compilador sem strip não tem hash igual ao APK, como esperado).
aapt legado badging falhou ao resolver o ícone de sistema sem framework; não foi
usado como validação completa. Os dois leitores de manifesto independentes
confirmaram package, versão, SDK31/35 e somente CONNECT/SCAN.

Certificado0.4difere da0.3; a chave privada temporária não permaneceu em disco.
Código, build scripts, AGPL e avisos acompanham o APK em pacote de fonte separado.
A nova função usa readRemoteRssi real no Android, mas o rádio desta Band e a UI
ainda precisam de teste físico; testes host só validam políticas com fixtures.
Nenhum novo dispositivo, pareamento, saúde, permissões ou serviço remoto foi acessado.
