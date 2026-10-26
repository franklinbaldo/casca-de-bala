# Cancelamento: contrato e verificação focalizada

O FAIL do gate anterior era uma expectativa de teste sem estado anterior registrado:
exigia CANCELLED mesmo quando a sessão já havia terminado. Não foi reproduzido
defeito no cancelamento de produção. A documentação do método e os testes foram
corrigidos; o comportamento de `HuamiAuthOwner.cancel()` foi preservado.

| Antes de cancelar | Depois | Fechamento |
| --- | --- | --- |
| NEW, ACTIVE, PEER_ACCEPTED ainda vivos | CANCELLED | exatamente uma vez |
| CANCELLED, BACKGROUND, REJECTED, FAILED já finalizados | preservar estado e causa | nenhuma repetição |

O teste host registra snapshots determinísticos e cobre cancelamento repetido,
callbacks antigos e causa de timeout. A Activity de fixtures registra antes/depois
atomicamente no mesmo callback principal. O teste focalizado no ART/API31 x86_64
passou com oito pares: três cancelamentos ativos com repetição, um após
PEER_ACCEPTED e um após WRITE_TIMEOUT real de cinco segundos. O último preservou
FAILED/TRANSPORT/WRITE_TIMEOUT, a chave já fechada e a contagem de fechamentos.
Não é correto exigir foreground para limpar uma sessão já finalizada. Uma primeira
execução do novo harness revelou essa expectativa indevida; o recibo a preserva.

PASS: contrato host/JNI e teste focalizado instalado. O SystemServer manteve PID
578 durante a execução final e seu crash buffer não mudou. NOT_RUN nesta rodada:
repetição da suíte completa de Activity, Band/celular reais e interoperabilidade BLE.
O resultado histórico do APK anterior continua histórico, não foi promovido a PASS.

O aplicativo de teste foi instalado com outro applicationId (`.cancelfixture`),
preservando o APK anterior `.activitytest` no AVD com hash verificado. Ao terminar,
o novo pacote foi removido, o emulador encerrado e as identidades temporárias ADB
e de assinatura apagadas. Nenhuma alteração de rede/segurança foi feita.

APK focal SHA256: `605686d733e0c6ef49cb505de6d497cbdfd97a10b6e5cd6bc68c9f1b1efded12`.
Fonte exata congelada desse APK: `cancel-fixture/source-when-apk-built.tar.gz`,
SHA256 `11ae59cf5ad9df464e5d649ab391aba1c1cfb7a5f0b99f8649dfb3723fe86a4b`.
As capacidades Rust acrescentadas depois não estão nesse APK: são testes host.
Logs e recibos de cleanup acompanham o pacote privado de preparação.
