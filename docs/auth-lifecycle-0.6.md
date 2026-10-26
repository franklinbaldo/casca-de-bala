# Dono da sessão AUTH — integração local, não GATT real

Esta etapa liga HuamiAuthOwner/BC1.86 ao SessionRust existente viaJNI. Não duplica
a máquina de handshake noJava. Nenhum APK novo, assinatura, credencial real,
pareamento, relógio/perfil/configuração, saúde, publicação ou Jatobá foi usado.
Todos os escalares usados nos testes são fixtures OpenSSL já publicadas. O teste
anterior que criava uma efêmera aleatória foi retirado desta rodada: nenhuma nova
chave foi gerada/configurada. A factory de produção não é chamada pelos testes.

## Fronteira implementada e recursos possuídos

A entrada é um payloadAUTH já reassemblado, com endpoint e geração. O transporte
fornecido é SIMULADO e copia somente comandosAUTH52/33 bytes produzidos noRust.
Não há UUID, conexão, framingTX, ACK físico, CCCD ou writeCharacteristic aqui.
As respostasJNI84 bytes são ABI interna do processo, não protocoloBLE/MCP.
Apenas chave pública/nonce do peer e prova cifrada atravessam essa ponte; chave
estática, escalar privado e chave de sessão ficam no contextoJava.

O dono possui exatamente um provider, um handleRust e um transporte. Cada nova
instância tem geração diferente; dentro dela há no máximo uma escrita pendente
com ticket próprio. Resposta ou callback de outra geração/ticket é descartado
antes de tocar na sessão atual. Não há reconexão/retry automático, nem reuso de
provider consumido. start é one-shot; nova tentativa exige dono/transporte novos.

O poolRust tem limite16 handles, IDs não reutilizados no processo e Mutex para
metadados. Não guarda chaves ou ponteirosJava. Erro/timeout remove handle; close
válido também remove, sem depender do relógio. Capacidade/buffers são validados
antes da mutação. JNI reserva saída antes de abrir handle para evitar vazamento
em falha de alocação da resposta. Buffer de saída máximo64 bytes, input máximo67.

## Ordenação, cancelamento e prazo

1. begin devolve comando de chave pública e aguarda desafio. Só um callback
   correspondente pode completar a escrita. Não confundir enfileirado com enviado.
2. Uma notificação pode chegar antes do callback de escrita. Há UM buffer de até
   67 bytes; ele é processado somente após confirmação. Segunda notificação antes
   de drenar fecha a sessão conservadoramente; não há fila sem limite/replay.
3. Desafio válido passa pela máquinaRust e provider real. Prova cifrada retorna
   aoRust para construir o comando. Sucesso antes de confirmação dessa escrita é
   mantido no buffer e só pode ser aceito depois; sucesso antes de desafio falha.
4. PeerAccepted é somente estado relatado pelo peer, não atestação independente
   nem autorização de operação protegida. O contexto continua possuído, sem API
   de consulta/cifra de framing ou capabilities prontas. Pode ser cancelado.
5. Cancelar, background/rotação, desconectar, erro de protocolo/crypto/transporte
   ou relógio fecham provider, transporte e handle. close idempotente e callbacks
   tardios não reabrem nada. onStop real ainda NÃO está ligado ao dono no app.

Prazo absoluto15s, inclusive PeerAccepted, é política deste pilotooffline, não
fato do protocolo. tick precisa ser chamado pelo futuro timerforeground. Se a
computação terminar após o prazo, a verificação antes da próxima escrita impede
enviar prova tardia. BC é síncrono: cancelamento não é preempção da multiplicação;
um adapter deve serializar eventos, medir latênciaART e não prometer UI não bloqueante.
Callbacks são confinados ao thread criador; outro thread é rejeitado sem mutar o
contexto. O adapter Android deve postar todos eventos nesse mesmo executor/looper.
Separar timeout de handshake e vida de sessão antes de autorizar consulta contínua.

## Evidência executada

PASS: HuamiCryptoTest e CryptoBridgeSmoke anteriores; AuthLifecycleTest usando
BC/JNI/Rust reais e transporte simulado. Cenários: fluxo ordenado, replies antes
de callbacks, callbacks antigos, rejeição/chave inválida sintética, transporte
recusado/exceção, cancelamento dentro de write, reinício recusado, deadline após
computação e após PeerAccepted, clock regressivo, background/desconexão, buffer
cheio, limite16/17,40 abre/fecha e callback de thread errado. Não há transcript
validado da Band. O sucesso do peer nos testes é fixture, não autenticação física.
RegressãoRust68 testes, fmt/clippy, JNI/leitor/SQLite/radar/survey/worker:PASS.
Android/NDKarm64 e compile/lint estrito/offline:PASS; ART/BLE/hardware:NOT_RUN.

Limpeza é best effort: testes observam provider fechado e liberação dos handles;
HuamiCryptoTest já verifica arrays próprios zerados. Isso não garante apagamento
em JVM/GC, BigInteger, SecretKeySpec, cópias do caller ou drivers. Transporte
futuro precisa liberar seus próprios buffers/timer/GATT ao receber close. Não
registrar payloadsAUTH/provas/chaves em log ou export de diagnóstico.

## Roteiro do próximo teste Android/ART — sem pulseira

Pré-requisitos: runtimeAndroid12+ARM64/emulador ou celular autorizado e APK de
harness explicitamente versionado/assinado. adb/emulator/dalvikvm não estavam
disponíveis no executor; nenhum acesso/configuração alternativo foi criado.
1. Harness usa só estas fixtures e FakeTransport, sem chamar a factory de geração
   de chaves, sem entrada de segredo e sem solicitar Bluetooth/localização/rede.
2. Repetir fluxo ordenado/early callback/timeout, cancelar em cada fase, alternar
   app, girar tela e repetir. A Activity de harness deve chamar background/close
   de verdade em onStop; timerforeground cancela ao sair e não retoma sozinho.
3. Confirmar bibliotecaJNI/BC carregadas noART, ausência de crash/ANR, no máximo
   uma escrita simulada em voo, recursos fechados uma vez e geração anterior
   ignorada. Mostrar só estados/contadores/versão, nunca bytes ou chaves.
4. Testar fontes grandes e navegação de volta apenas como confirmação do ciclo
   de vida; não ampliar UI/radar como objetivo. Registrar PASS/FAIL/NOT_RUN noART.
Permissões desse harness simulado: NENHUMA nova. Não pedir CONNECT/SCAN só para
rodar fixture. CompilaçãoNDK/JVM não substitui esses resultadosAndroid.

## Gate para APK de piloto com Band real

Ainda faltam adapterGATT real (serviços/características verificados por fonte e
discovery, geração/identity de BluetoothGatt, escrita serial, CCCD/framing/ACK),
serviços/cifra por endpoint e consulta proprietária não mutante definida. Receiver
atual ainda rejeita frames cifrados; não chamar bytes cifrados de Activity8.
Também falta entradaLOCAL voluntária de chave já do dono, sem logs/chat/extração
ou armazenamento novo; provider não é configurado com credencial nesta entrega.
Decisão de assinatura/versão e fonte correspondente precede gerar APK; não criar
chave estável/temporária por inferência da preparação local.

Permissões do piloto real: CONNECT para dispositivo já vinculado; SCAN somente
se o usuário escolher buscar anúncios. Não requer localização, saúde, Internet,
HID/BLUETOOTH_PRIVILEGED, novo vínculo ou serviço em background. Permitir negar
SCAN e continuar pela lista de vinculados. Nunca resetar/desparear ou remover
ZeppLife; encerrar nRF antes do ensaio se ele estiver usando a conexão.

Roteiro físico SOMENTE após aceite explícito: selecionar unidade/modelo/software,
confirmar objetivo de uma tentativa AUTH e uma consulta de estado; ler chave
apenas pela entrada local já aprovada; assinar/despachar só AUTH/CCCD necessários,
sem phase2/phase3 automática/setCurrentTime/perfil. Parar no primeiro timeout,
rejeição, prompt de vínculo inesperado ou protocolo não reconhecido; não retry em
loop. Anotar apenas estágios/códigos/contadores sanitizados. Depois testar cancelar,
reconectar por ação humana com geração e efêmera próprias, fechar app e confirmar
que nada continua. Coleta pessoal, configurações, localizar/vibrar, apps/OTA têm
consentimentos/gates separados. Sem esses pré-requisitos, APK não deve oferecer
botão que prometa autenticação/consulta prontas.
