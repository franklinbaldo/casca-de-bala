# Sessão cancelável e retomável — caminho host executado

src/sync_session.rs integra Receiver Huami2021, fila limitada, Activity8/ActivityDb,
token cooperativo e snapshot de progresso. Esta sessão é offline e somente
Activity8. Não é o worker Java/JNI síncrono nem coleta física.

## A fronteira que continua faltando

receive_plaintext usa o Receiver existente: remonta notificações estendidas
plaintext e devolve Message(endpoint,payload). **Não** chama o decoder de atividade.
Não há endpoint presumido, novo envelope BLE, UUID, autenticação, cifra ou ACK
transmitido. Fonte do framing: [referências Huami](huami-transport.md); fonte do
Activity8/minuto: [cobertura e FetchActivityOperation](data-coverage.md).

O método enqueue_activity8 é uma API interna do host para um fetch **já
selecionado, autenticado e decifrado**, com primeiro instante Unix/offset resolvidos.
O fetch adaptador deve verificar identidade/categoria/integridade e reconhecer
payload, cabeçalho temporal e fim da transferência conforme o protocolo real.
Essa classificação não está implementada. O endpoint 0x1234 usado no teste é
arbitrário/sintético, não endpoint de Activity8. O teste prova explicitamente que
receber esses bytes não os persiste; só o adaptador de fixture chama enqueue.
Não usar esse teste como captura ou prova da ponte real Android ↔ miniapp.

## Fluxo executável e limites

1. Abrir SyncSession para arquivo SQLite/origem/janela esperada imutáveis.
2. Opcionalmente receber fragmentos via receive_plaintext e agendar
   expire_transport mesmo sem novas notificações; deadline vem do Receiver.
3. Após o limite de fetch confiável, enqueue_activity8 recebe bytes (não envelope
   wire inventado), timestamp/offset e geração de sessão atual.
4. process_next decodifica e grava um lote transacional; só devolve Committed
   após commit SQLite. progress consulta snapshot consistente de linhas/gaps/cursor.
5. Cancelar/reconectar ou fechar/reabrir: dados confirmados permanecem e a
   aplicação precisa refazer fetch desde cursor/lacunas, não do maior timestamp.

Fila máxima de 2 lotes, cada um até 4096 registros/32768 bytes (64 KiB de payload
retido na fila), sem expansão automática. Admissão cheia retorna Backpressure
antes de copiar/consumir o input; caller pode drenar e tentar novamente, sem
assumir recepção remota concluída. Processamento síncrono retira um lote antes
do commit. Window/decoder/queries têm seus próprios limites de 4096 registros;
o Receiver limita a mensagem parcial a 4096 bytes. Não é medição de RSS total:
clones/metadata do decoder/DB também consomem memória limitada por esses budgets.
Caller não deve acumular uma lista ilimitada de mensagens recebidas fora da fila.

Não há worker/threadpool criado pelo componente nem retry automático. Um actor
Tokio/worker Android futuro deve dirigir process_next/expire e aplicar backpressure
à origem. Os 512 bytes de notificação do Receiver são política local, não MTU
comprovado na Band. Cadência de minuto só se aplica a Activity8, não HR/SpO2.

## Cancelamento, reconexão e commit

Cancellation é token Arc/AtomicBool clonável que outro thread pode solicitar.
Chamadas da sessão observam o token e descartam fila/parcial não confirmados.
process_next verifica token antes do trabalho e via ActivityDb.ingest_checked
**após** staging das linhas/checkpoint, imediatamente antes do commit; token
ativo nesse ponto causa StoreError::Cancelled e rollback real. Teste determinístico
usa hook privado para solicitar cancelamento em outro thread nesse ponto, com
SQLite real; fecha/reabre e confirma ausência de registros/cursor parciais.

O cutoff é a última verificação: pedido posterior pode concorrer com COMMIT e não
desfaz um lote confirmado. Resultado de sucesso deve ser consumido/reconciliado;
progress pode indicar pausado com dados já confirmados. Não prometemos interrupção
por linha, prazo de cancelamento, abort de syscall SQLite ou hard timeout.
Close/drop de conexão não é mecanismo de cancelamento de operação concorrente.

reconnect aloca geração nova, cancela token antigo, limpa fila e parcial, preserva
DB e cria token novo. Gerações são únicas no processo, inclusive após novo open;
saturação falha fechada sem wrap. Callbacks antigos são rejeitados antes de tocar
na sessão atual. Tokens não são credenciais/autenticação e não são persistidos
para identificar conexão depois de morte do processo. Handlers/callbacks devem
ser descartados ao reiniciar o processo. Nenhuma mensagem parcial é retomada.

Conflito ou erro de store pausa a sessão e esvazia pendências. Requer reconexão e
reconciliação explícitas; não faz retry cego após falha de IO/commit incerto.
Replays Activity8 idênticos são idempotentes; lotes fora de ordem podem preencher
gaps no DB. Fragmentos Huami fora de ordem/duplicados são rejeitados pelo Receiver;
não fazemos reorder de wire sem evidência. Cancelar durante uma parcial não
avança cursor. Timestamp/offset continuam responsabilidade do fetch confiável.

## Progresso e regressões

Progress fornece cursor opcional, stored/missing na janela, pending_batches,
queued_bytes, transport_partial, paused e window_ready. window_ready só é true
sem gaps, sem pendências/parcial e sem pausa. Significa **somente janela Activity8
coberta**, não fetch concluído, histórico total, saúde completa ou dispositivo
integrado. Não substitui a enumeração de categorias/tipos de disponibilidade.
Nenhum valor de saúde é logado, enviado a MCP ou publicado.

```sh
# Execute na raiz do projeto
cargo test --offline sync_session
cargo test --offline --test sync_session
cargo test --offline
cargo fmt --all -- --check
cargo clippy --offline --all-targets -- -D warnings
# Na raiz do repo:
sh android-bridge/run_host.sh
```

Seis testes novos (um unitário + cinco de integração): framing não classifica
payload; fixture pós-fetch → decoder → SQLite → reabertura; interrupção de parcial
e entre commits, callback/token antigo e geração após reopen; fila/bytes máximos,
backpressure e dados atrasados; duplicatas/conflicto/rollback; timeout e sequência
inválida; cancelamento após staging SQL sem avanço durável. Fixtures sintéticas
em texto e testes com arquivo real, não mock de DB. Nenhuma evidência essencial
só em /tmp; arquivos de teste são removidos.

Sessão nova não está exposta na ABI C/JNI nesta rodada; o harness Java/JNI anterior
foi reexecutado como regressão e continua síncrono sem cancelamento nativo exposto.
Coleta/auth/fetch físicos, cifra e integrações por categoria: NOT_RUN.
Para avançar no transporte real faltam unidade identificada e evidência autorizada
do fetch (negociação, timestamps, payload Activity8 e fim/ACK), sem inferir vínculo
pelo hmBle/Side Service. Nesta etapa nenhum relógio/celular foi conectado nem
permissão/credencial criada. O miniapp permanece installable:false.
