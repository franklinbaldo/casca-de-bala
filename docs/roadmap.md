# Rota prioritária: Android + miniapp no firmware original

Decisão de 2026-10-24, após pesquisa trazida pelo usuário: começar pelo terminal
JS/API1 no firmware original e pelo aplicativo Android próprio. Firmware nativo
permanece pesquisa separada; boot próprio não é pré-requisito dessa rota.
Acesso integral continua requisito-alvo, não capacidade demonstrada.

Arquitetura: MCP → comandos tipados Rust no Android → adaptador Kotlin/GATT →
protocolo Huami/Zepp OS → miniapp. Kotlin cuida de permissões e ciclo de vida;
Rust dos contratos e estado das operações; JS da tela/toque local. Nenhum desses
adaptadores está implementado. O ponto de maior incerteza é comunicação direta
entre Android independente e miniapp; instalar um pacote não resolve esse canal.

## Entregas e critérios de aceitação

| Etapa | Trabalho e resultado exigido | Parada / estado atual |
| --- | --- | --- |
| 0 — contratos executáveis | Resolver dependências, gerar lock, compilar, rustfmt, testes e clippy | PASS nesta continuação: 11 testes Rust, fmt, clippy e lock gerado |
| A — protocolo específico e identificação | Mapear autenticação, negociação, serviços, framing, confirmações e erros no commit Gadgetbridge fixado; associar cada decisão à fonte. Quando autorizado, registrar modelo/variante/firmware/hardware e pareamento da unidade | Sem credenciais novas ou conexão nesta entrega; não inferir deviceSource 263 |
| B — miniapp mínimo offline | Tela com texto e botão, APIs 1.0, ID próprio sem colisão conhecida, pacote gerado pelo builder verificado; registrar fonte, versão da ferramenta, manifesto, bytes e digest | Fonte UI mínima e 3 testes Node PASS; build/instalação NOT_RUN; não rotular ZIP como pacote validado |
| C — instalação e remoção controladas | Na unidade identificada e autorizada, comparar listas antes/depois, abrir miniapp, remover somente o artefato próprio e demonstrar retorno ao estado anterior | Sem recuperação adequada, autorização ou classificação inequívoca, não escrever/remover; resultado incerto exige reconciliação |
| D — cartão e toque de volta | Android envia cartão, Band renderiza, botão gera evento que volta ao Android com correlação; repetir após desconexão, tela fechada e retomada | Rota Zepp/Side Service e rota BLE independente são ensaios distintos; sucesso de uma não aprova a outra |
| E — MCP | Expor somente operações verificadas; autorização, cancelamento, duplicatas, expiração e resultado incerto; medir latência e consumo com budgets prévios | Nenhum endpoint público ou acesso persistente novo nesta entrega |

A rota Zepp é referência de laboratório opcional, não dependência definitiva.
Zeus CLI 1.8.2/target mi-band7-intl são relato do WatchDrip para variante
internacional, não identificação automática da unidade. ZMake é candidato ao
build local; fixar versão e conferir licença antes de instalar qualquer ferramenta.
Nenhum APK modificado, reset ou novo pareamento está autorizado por este plano.

## Proteção de apps de sistema

Requisito obrigatório antes de habilitar gestão real, **ainda não implementado**:

- Negar remoção de sistema, artefato desconhecido ou classificação ambígua.
- Autorizar somente artefatos do projeto com registro local confiável de
  instalação na mesma unidade: classe, ID real reconciliado, pacote/digest,
  versão e resultado confirmado. Declaração `owned:true` do cliente não basta.
- Negar instalação que sobrescreva ID existente não pertencente ao projeto.
- Antes de remover, reler estado e validar o vínculo; mudança de firmware,
  pareamento ou divergência de inventário invalida a confiança anterior.
- Distinguir remoção na Band de resíduos no telefone. Não usar limpeza recursiva
  de caminhos fornecidos pelo pacote ou cliente como substituto da operação.
- Ícone desaparecido não prova remoção completa; timeout não prova falha.
  Reinicialização necessária exige plano/autorização próprios, sem retry cego.

O contrato `Authorizer` deverá consultar esse estado do host, além da aprovação,
antes de permitir mutação. A política atual deny-all continua negando todas;
não foi criado inventário persistente nem verificador de propriedade nesta etapa.
Gestão genérica de arquivos é investigação; não adicionada às capacidades MCP.

## Android e ciclo de vida

Para target Android 12+, SCAN/CONNECT dependem da operação e aprovação runtime;
não pedir ADVERTISE se não houver publicidade BLE. Escolher SDK mínimo/alvo
antes do manifesto. Conexões prolongadas precisam de estratégia de ciclo de
vida compatível: connectedDevice foreground service ou APIs de companion,
considerando restrições de início e término do processo. Rust não substitui
essas regras. Chave Huami deve permanecer em armazenamento seguro no Android,
sem logs, Git, exemplos reais ou entrada nesta conversa.

Fontes primárias: [permissões Bluetooth](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)
e [comunicação em segundo plano](https://developer.android.com/develop/connectivity/bluetooth/ble/background).
O miniapp não deve ser presumido daemon permanente. Testar saída da tela,
suspensão e retorno; um temporizador especial não prova serviço universal.


## Frente de computação local — micro-VM v1

O protótipo offline B7VM acrescenta bytecode sem loops ou efeitos externos,
um encoder/validador Rust e um interpretador JS, com vetores em comum.
Isto não altera o protocolo BLE e não libera `band.exec`: a operação continua
privilegiada e unsupported. Sequência: validar com Node/Rust, comprovar build
de miniapp na API1, embutir programa estático, executar na Band real, medir e
**somente então** avaliar transporte dinâmico de programas.

O benchmark inicial compara bytes B7VM x JSON para os mesmos programas, sem
pretender comparar bibliotecas FlatBuffers/Protobuf/CBOR/MessagePack que ainda
não foram integradas. Ver [microvm.md](microvm.md).
