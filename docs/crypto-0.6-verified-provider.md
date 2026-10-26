> Estado da validação anterior. O binding de ciclo de vida simulado foi
> acrescentado em [auth-lifecycle-0.6.md](auth-lifecycle-0.6.md); GATT/ART continuam pendentes.

# BC1.86 — validação local autorizada, não autenticação física

Esta atualização sucede o gate de instalação em crypto-0.6.md; o ensaio anterior
com1.79 continua histórico. A autorização de ferramentas oficiais permitiu
obter1.86 para testes, sem chave real, Jatobá, aparelho ou nova release.

## Origem, licença e integridade

A página oficial BC indicou bcprov-jdk18on1.86; Maven Central disponibilizou o
POM dessa versão com URL do fornecedor, SCMbcgit/bc-java e licença BC/MIT.
Checksums publicados foram lidos antes do JAR, verificados antes de carregá-lo:
JAR SHA2562af190b300cbb0b35e248ccf5f4a06b6072030aeb3da7a98ec73abe5b4cb371f;
POM SHA256970b70cb75a184639b6fb9ba3c465aca6a4e1a5f3fff4d65ce8574b2fd79ef38.
Registros/URLs em third_party/bcprov-1.86-download.json, licença exata do JAR em
third_party/licenses/bcprov-1.86/LICENSE.md. Nenhum JAR foi vendorizado no Git.
Isso verifica integridade viaHTTPS/checksum do registry, não certificaçãoFIPS,
assinaturaPGP independente, ausência de vulnerabilidades ou segurança da Band.

Gradle agora fixa implementação1.86 e seus dois hashes na verificação estrita,
sem gerar metadata nova no build. Fonte crypto-src é compilada pelo módulo
Android. A primeira resolução online falhou noDNS da JVM; não alteramos rede,
proxy, tokens ou validaçãoTLS. Usamos um espelho LOCAL contendo exatamente o
POM/JAR oficiais já verificados, opção band7BcRepository filtrada só para1.86,
e repetimos compile/lint com --offline --dependency-verification=strict.

## Implementação e fluxo realmente exercitado

HuamiSessionCrypto é backend localJava: geração efêmera BC com SecureRandom,
B163/sect163r2, wireX/Y24LE, ECDHBasicAgreement validando ponto, derivação da chave
X[8..24] XOR authKey e dois blocos AES/ECB/NoPadding conforme InitOperation2021.
Apenas nonce e chave pública do peer podem cruzar a JNI do teste. Chave estática,
escalar privado e chave de sessão permanecem Java-only. Não registrar provider
globalmente nem substituir o provedor Android. Não adicionar teclado/armazenamento
ou pedir chave ao usuário nesta fatia. Não há envioGATT, ACK ou comando físico.

O novo b7_auth_reply_kind é stateless, limitado67 bytes, classifica envelope e
não autentica. JNI encaminha apenas peer-payload; recebe tipo, não segredo.
CryptoBridgeSmoke percorreu fixture pública→JNI real→decoder Rust→BC1.86.
A máquina de estadosRust anterior continua testada, mas NÃO está conectada a um
controladorGATT/CCCD real ou a um ciclo de vida Android do provider. PeerSuccess
continua informação de peer, sem desbloquear comandos protegidos. Nenhuma escrita
de hora/perfil de phase2/phase3 foi incorporada.

## Testes e limites

PASS: vetor primitivo publicado OpenSSL3.5.7 sect163r2 em BC1.86; AES conhecido;
composição sintética da derivação, wire/public key e contadorLE. A composição
não é transcriptHuami conhecido e não prova interoperabilidade com Band7.
Negativos: tamanho/nonce nulo, curva/padding não canônicos, ponto inválido,
infinito/ordem2, contexto consumido/fechado; saída ou exceção sem segredo em logs.
A falha da referênciaECDH_B163 é uma observação do harness com ponto sintético e
escalar par na revisão fixada, não vulnerabilidade geral comprovada noGadgetbridge.

PASS:67 testesRust, fmt/clippy; harnesses JNI/leitor/SQLite/radar/survey/worker;
providerJava e CryptoBridgeSmoke -Xcheck:jni; build nativo arm64/NDK27 com símbolo
C/JNI exportado; compileDebugJavaWithJavac e lintDebug Android. Lint0 erros e18
avisos; aviso novo ECB foi mantido explícito: modo exigido para nonce16 bytes
nesse protocolo, não uso geral/armazenamento. Não o suprimimos globalmente.

NOT_RUN: ART/emulador/telefone (adb/emulator/dalvikvm não disponíveis), autenticação
BLE, credencial real, providerJNI ligado a controlador de sessão real, consulta
proprietária, histórico de saúde e instalação de apps. Não geramos APK ou chave
de assinatura. Releases0.4/0.5 e patchPillow permanecem intactos/separados.

## Memória e próximo gate

Provider copia a entrada, consome efêmera uma vez, zera seus arrays mutáveis em
close/erro e descarta referências. Caller possui a entrada e cópias da prova;
precisa cuidar delas. Testes observaram buffers próprios zerados e acesso após
close recusado. NÃO garantimos apagar BigInteger imutável, SecretKeySpec/cipher
internos, cópiasJVM/GC, IME, dumps ou caller. A chave de sessão fica no contexto
Java atéclose; por enquanto não é oferecida ao Rust nem a uma cifra de framing.
O dono futuro da sessão deve fechar esse contexto em cancelamento, timeout,
exceção, onStop e mudança de geração. Esse binding ainda é um gate, não promessa.

Próximo passo local: conectar provider e controlador por handles de sessão
limitados, testando cancelamento/gerações e falhas sem expor segredo. Depois
validar ART e fluxo explícito de entrada de chave já do usuário; sem extração ou
chat. Só com consentimento específico testar handshake+consulta não mutante no
aparelho, excluindo inicialização de hora/perfil e coleta de saúde automática.
