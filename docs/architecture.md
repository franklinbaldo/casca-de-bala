# Arquitetura de mensagens — decisões e gates

Estado: arquitetura-alvo. O Android atual é Java com leituras básicas GATT;
atores Rust/Kotlin e MCP remoto abaixo são propostas, não recursos implementados.
Dispositivo alvo: **Xiaomi Smart Band 7 comum**, não Pro.

## Camadas (não confundir seus formatos)

1. **MCP:** manter o protocolo e os requisitos de JSON-RPC da interface
   externa. Não substituir o wire format MCP por FlatBuffers.
2. **Android/Kotlin:** permissões, BluetoothGatt, conexão/ciclo de vida,
   callbacks, serviços e armazenamento seguro de chaves Huami; nenhuma chave
   real nos logs ou no repositório.
3. **Rust:** núcleo de comandos tipados, autorização, reconciliação e estado.
   Entre atores no mesmo processo, preferir canais com tipos Rust: não há
   motivo para serializar bytes em cada envio interno.
4. **JNI Kotlin ↔ Rust:** comparar custo de marshalling, cópias e buffers.
   Considerar ByteBuffer e serialização binária só quando a medição justificar.
5. **Adaptador BLE:** obedecer autenticação, framing, fragmentação, MTU e
   operações reais do protocolo Huami/Zepp OS da Band 7. Não inventar GATT
   ou substituir o protocolo do dispositivo por FlatBuffers.
6. **Miniapp JS/API1:** comunicação bidirecional independente ainda não
   demonstrada. O side service do Zepp e um cliente BluetoothGatt próprio
   não são o mesmo canal. B7VM v1 é apenas bytecode local experimental.

## Concorrência do núcleo Rust

Planejar Tokio com tarefas assíncronas supervisionadas e canais limitados:

- Command Actor: validação, controle de acesso, IDs e despacho.
- Device Actor: sessão, estado, capacidades e reconciliação.
- BLE Actor: estado de conexão, ordem de operações, fragmentação e ACKs.
- UI Actor: cartões, eventos e coalescência de atualizações obsoletas.
- Program Actor: programas verificados, budgets e resultados locais.

Usar mpsc limitado para filas, oneshot para respostas e watch para mudanças
de estado. Priorizar eventos interativos sem impedir operações de controle.
Não usar filas infinitas ou busy-wait. Não criar thread por mensagem.

Um ACK de transporte não é confirmação de instalação, alteração da tela ou
execução de programa. Mutação com timeout entra em **resultado desconhecido**
e exige leitura/reconciliação; jamais retry automático cego. Cancelamento não
desfaz necessariamente operações já transmitidas.

## Seleção do formato binário

Candidatos: FlatBuffers, Protocol Buffers, MessagePack, CBOR e binário
especializado. Comparar *a mesma carga semântica* por encoder real nos
extremos pertinentes. Medir tamanho, encode/decode, cópias, alocação,
interoperabilidade com API1 JS, CPU e consumo. O bytecode
[B7VM v1](microvm.md) tem propósito específico, não é decisão universal
de formato de mensagens. O pequeno benchmark de tamanho JSON x B7VM não
permite concluir que B7VM vence FlatBuffers.

## Testes com etapas observáveis

Instrumentar IDs de correlação e timestamps monotônicos de enqueue,
dequeue, ponte JNI, chamada BluetoothGatt, callback, ACK de aplicação (se
observável), atualização de UI e retorno do toque. Relatar p50/p95/p99,
bytes, retransmissões, memória, CPU e energia quando houver instrumentos.
Separar simulador, Android físico e Band 7 física.

Gates: Rust offline → Android GATT autenticado → instalar/remover **somente
artefato próprio** e reconciliar → cartão/toque ↔ Android → benchmark real.
Não tocar em firmware ou remover software de sistema sem os gates de
recuperação específicos.

Incremento implementado: [Receiver Huami2021 offline](huami-transport.md),
independente do formato de mensagens da aplicação, B7VM e filas planejadas.
Ele aceita notificações fornecidas pelo chamador e não realiza conexão, ACK ou
cifra. Atores e adaptador Android continuam gates posteriores.
