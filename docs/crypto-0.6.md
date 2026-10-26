> Estado histórico da primeira sonda. A instalação1.86 foi autorizada e a
> validação posterior está em [crypto-0.6-verified-provider.md](crypto-0.6-verified-provider.md).

# Escolha e validação criptográfica — preparação local

## Decisão e limites

AES do handshake: preferir JCA Android `AES/ECB/NoPadding`, exatamente o modo
usado pelo upstream para cada nonce de16 bytes; não generalizar ECB para dados
arbitrários ou armazenamento. SunJCE host passou no vetor conhecido FIPS197
(original, AppendixC) key000102…0f/plaintext001122…ff/ciphertext69c4…c55a.
Isso não certifica Android/ART, backend do telefone ou protocolo completo.

ECDH-B163/sect163r2: o SunEC do JDK local recusou a curva. Avaliamos a classe
ECDH_B163 Java do Gadgetbridge a0948ee1cbc2a870f91d313f8e37df5f524465f7 como
referência de wire24/48 bytes little-endian, sem editar sua implementação.
Ela passou nos pontos públicos e compartilhadosX publicados no teste do
OpenSSL3.5.7, mas aceitou ponto sintético de ordem2 e retornou compartilhado
zero com escalar par. Não selecionada diretamente para produção. Não extrapolar
esse ensaio para exploração da Band ou de outra aplicação; não houve aparelho.

Bouncy Castle1.79 já estava no cache Gradle, hash SHA256 conferido contra a
metadata fixa da0.5. Usado SOMENTE como sonda host: passou no mesmo vetor de
compatibilidade e rejeitou aquele ponto de ordem baixa. Não é aval de segurança
para distribuir versão antiga. Proposta para próxima etapa: revisar/autorizar
bcprov-jdk18on1.86, versão oferecida pela fonte oficial consultada, fixar hashes e
licença, repetir vetores/negativos, testar API e build Android antes de incluir.
Nenhum JAR novo foi baixado/instalado nem dependência adicionada ao aplicativo.
A API de acordo validada foi ECDHBasicAgreement com parâmetros sect163r2 e
ECPublicKeyParameters; não trocar por ECDHC/cofactor ou X25519 por semelhança.

## Fonte verificável dos ensaios

O vetorECDH é do arquivo upstream OpenSSL, tagopenssl-3.5.7,
test/recipes/30-test_evp_data/evppkey_ecdh.txt, bloco TEST CURVE sect163r2.
EsperadoX =040df54e6df412790ef5c0fafbbfcba5136b872951. O harness extraiu
os parâmetros das chaves de TESTE já publicadas; não gerou um esperado a partir
da implementação sob teste. Conferiu duas chaves públicas e dois resultadosX.
Comparação deY entre implementações/pares é evidência complementar, não um vetor
independente paraY nem capturaHuami. Os escalares são fixtures públicas, não
credenciais. Não gerar chaves de conta/dispositivo para essa validação.

A busca do arquivoNIST deECCCDH direto foi bloqueada por403 do transporte; o
mirror PyCA consultado não contémB163. Não declarar testeNIST-B163 executado.
Além disso, ECCCDH NIST usa cofactor; não comparar seuZZ diretamente ao dQHuami
sem conferir a definição. Usamos o vetor explícito do upstream OpenSSL.
O caso de ordem2 é teste matemático sintético, não prova de interoperabilidade.

Licenças: a classe ECDH_B163 e InitOperation2021 consultadas declaram
GPL-3.0-or-later, com copyright e avisos conservados. O derivado local segue
AGPL-3.0-or-later do projeto; não relabelar a cópia upstream. GPLv3§13 trata da
combinação com AGPLv3. O autor atribui a base a kokke/tiny-ECDH-c; essa cadeia
exige conservação de avisos e revisão antes de qualquer distribuição de backend.
Vetores OpenSSL sãoApache-2.0 (licença e copyright preservados no pacote privado).
Bouncy Castle tem licença permissiva lida comoMIT; preservar seu aviso se entrar
no app. Nenhum código/JAR BC foi vendorizado nesta fatia.

## Integração de estado local

huami_handshake.rs usa o decoder existente. Gera somente mensagensAUTH com
layout upstream: chave pública52 bytes, resposta de prova33 bytes. Provedor
externo ainda precisa gerar/validar chave pública e calcular os dois blocosAES.
O Rust atual NÃO faz isso nem recebe credencial bruta. Uma sequência sintética
não torna autenticada uma sessão. A máquina expõe PeerAccepted, sem API para
liberar comandos protegidos ou classificar autenticação criptográfica como pronta.

Teste inclui geração antiga, relógio regressivo, prazo absoluto15s (política de
laboratório, não fato do protocolo), cancelamento, resposta prematura, erro de
envelope, desafio repetido, falha e sucesso após confirmação de escrita da prova.
O adaptador futuro deve correlacionar callback real da escrita e enfileirar ou
rejeitar ordenadamente notificações que chegam antes dele; esse comportamento
Android não foi medido. Não há GATT writer/CCCD ou troca real de chaves aqui.

A inicialização upstream, após sucesso, chama setCurrentTimeWithService e
phase2Initialize/phase3Initialize. Nosso controlador não as chama, não altera
perfil/hora e não dispara outras operações. Primeiro ensaio físico futuro deve
limitar-se a handshake e uma consulta de estado; AUTH/CCCD precisam de escrita
BLE autorizada, embora o dado consultado não seja mutante. Falha fecha sessão.

## Entrada local segura de chave — somente proposta

- Usar somente uma chave já legitimamente disponível ao dono; não extrair,
  procurar arquivos de outros apps ou pedir segredo no chat. Sem via aprovada,
  apresentar bloqueio, mantendo leitor/radar básicos.
- Tela local explícita e mascarada, sem logs, serialização de requests, analytics,
  prévia de compartilhamento, sugestão/autofill ou cópia automática para clipboard.
  Revisar proteção apenas da entrada sensível sem bloquear prints da interface
  comum. Não conectar, criar vínculo ou persistir por efeito de digitar a chave.
- Padrão: uso só em memória enquanto autorizado. Java/provider guarda a chave e
  gera efêmera com SecureRandom; Rust recebe referência opaca e resultados de
  operações, nunca JSON/string com segredo. Reduzir cópias e limpar buffers em
  falha/close; não prometer apagamento perfeito em JVM/GC ou memória capturada.
- Se Franklin escolher guardar: decisão separada para chave de embrulho Android
  Keystore e AES-GCM, sem backup/export. A Huami auth key precisa aparecer emRAM
  para a derivaçãoXOR descrita pelo protocolo; não afirmar que ela jamais sai do
  Keystore. Não criar/configurar essa credencial de armazenamento agora.
- Provider valida tamanho, curva/canonicalidade/subgrupo conforme backend, ponto
  infinito/compartilhadozero e associação da prova a geração/desafio. Sem backend
  validado noAndroid, estado PeerAccepted não vira capacidade liberada.

## Resultados e próximo gate

PASS host: vetorOpenSSL B163 na referência e BC cache; AES conhecido; controle
negativo rejeitou a seleção direta da referência; Rust66 testes, fmt e clippy.
Teste de limite não é aprovação da referência: demonstrou uma restrição real.
Nenhuma alegação de tempo constante, auditoria formal, certificaçãoNIST/FIPS ou
interoperabilidade física. NOT_RUN: backend1.86, ART/buildAndroid, JNI provider,
transcript completoHuami, consulta, relógio/saúde/aparelho reais. Pillow separado.
Próximo gate: aprovação do pin/provider novo, vetores e negativos noAndroid,
provider→estado com sessão efêmera, depois consentimento para ensaio controlado.

Fontes: [OpenSSL3.5.7](https://github.com/openssl/openssl/blob/openssl-3.5.7/test/recipes/30-test_evp_data/evppkey_ecdh.txt),
[FIPS197](https://nvlpubs.nist.gov/nistpubs/FIPS/NIST.FIPS.197-upd1.pdf),
[BC distribuição](https://www.bouncycastle.org/download/bouncy-castle-java/),
[BC licença](https://www.bouncycastle.org/licence.html),
[upstream ECDH](https://github.com/Freeyourgadget/Gadgetbridge/blob/a0948ee1cbc2a870f91d313f8e37df5f524465f7/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/util/ECDH_B163.java).
