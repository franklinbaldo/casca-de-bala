# Protótipo local de miniapp API1

Fonte mínima com cartão, botão e confirmação de toque local. `app.js` e
`page/index.js` usam os globais App, Page e hmUI. Eventos são mantidos somente
na memória da página; não são enviados ao Android. O contador satura em 65535,
reinicia ao criar a página e callbacks antigos são ignorados após destruição.
Não há sensores, arquivos, instalação, rede, BLE ou Side Service.

Teste offline com Node, sem dependências:

```sh
node --test miniapp/tests/page.test.mjs
```

O teste usa uma API hmUI simulada: verifica layout proposto 192×490, atualização
local por toque e ciclo de vida. Não testa fontes, renderização ou execução no
relógio. O layout e as APIs precisam de validação física.

Ainda não é pacote instalável: faltam manifesto do alvo, ID livre, ícone e build
com versão fixada de ZMake/Zeus. Não há appId/deviceSource escolhido por inferência
nem BIN/ZIP criado manualmente. Gerar esses dados após identificar a unidade,
conferir ausência de colisão e preparar o builder; instalação exige os gates
do [roteiro](../docs/roadmap.md).

Fontes primárias consultadas para os campos de UI (API1):
[TEXT](https://docs.zepp.com/docs/1.0/reference/device-app-api/hmUI/widget/TEXT/)
e [BUTTON](https://docs.zepp.com/docs/1.0/reference/device-app-api/hmUI/widget/BUTTON/).
Código próprio; não deriva das rotinas de arquivos/remoção do Toolbox.


## Micro-VM v1 separada

O arquivo [vm/runner.js](vm/runner.js) é um interpretador JavaScript
determinístico e sem efeitos externos, com vetores compartilhados com Rust.
Execute `node --test miniapp/tests/vm.test.mjs` a partir da raiz do crate.
Ele ainda **não** está incluído em page/index.js ou em pacote instalável.
Sem verificação de compatibilidade API1 na Band real, não declarar execução
local da VM comprovada. Ver [contrato e limites](../docs/microvm.md).

## Preparação de pacote

[Preparação reproduzível e inspeção offline](packaging/README.md): bundle canônico
de fontes com hashes e gates pendentes, mais inspetor de payload ZIP/BIN com
limites. Não gera pacote instalável. Builder de terceiros não foi executado;
manifesto, plataforma, assets e adaptação de runtime ainda exigem aprovação e
validação. A extensão BIN sozinha não comprova compatibilidade física.

Atualização de 25/10: [builder ZMake executado e candidato de laboratório reproduzível](packaging/README.md) — pacote estrutural inspecionado, instalação e perfil da unidade ainda não validados.
