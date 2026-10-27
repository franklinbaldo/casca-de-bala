# Servidor MCP no celular

O Casca é o hub entre os seus dispositivos e as suas IAs. O próprio celular
serve MCP (Streamable HTTP, respostas JSON); não há servidor intermediário.

## Ligar e parear

1. No app, toque em **Ligar servidor MCP**. Ele pede Dispositivos próximos e
   notificações, e mostra o endereço na tailnet: o nome MagicDNS
   (`http://celular.tailnet.ts.net:8765`) ou, sem MagicDNS, o IP `100.x.y.z`.
2. Na máquina da IA, dentro da tailnet:

   ```sh
   curl -N -X POST http://100.x.y.z:8765/pair -d '{"client":"jatoba"}'
   ```

   A resposta mostra um código. No celular chega a notificação
   **Liberar acesso ao Casca?** com o mesmo código: toque em **Permitir**.
   O `curl` termina com `CASCA_TOKEN=casca_…`.
3. Configure o cliente MCP com esse token como Bearer. No Claude Code:

   ```sh
   claude mcp add --transport http casca http://100.x.y.z:8765/mcp \
     --header "Authorization: Bearer casca_…"
   ```

Aprovar dá acesso a tudo que o celular intermedeia: sem lista de escopos por
padrão. Os tokens ficam em armazenamento privado do app e sobrevivem a
reinícios.

## Ferramentas

| Ferramenta | O que faz |
| --- | --- |
| `casca_capabilities` | Lista os dispositivos e o que cada um oferece agora. |
| `band_devices` | Pulseiras vinculadas ao celular (só LE/dual), com id opaco: os 12 primeiros hex do SHA-256 do endereço. |
| `band_read` | Conecta e lê bateria, revisões e PnP agora, com o app fechado. `device` opcional: sem ele, usa a última lida ou a única vinculada. |
| `band_snapshot` | Última leitura feita (pelo app ou por `band_read`), sem conectar. |
| `band_notify` | Mensagem no pulso: `title` (até 64) e `body` (até 512). Vira uma notificação no celular, que o app que conduz a pulseira leva ao pulso com vibração. |

Nenhuma dessas ferramentas pareia, escreve na pulseira ou expõe endereço
Bluetooth. `band_notify` só cria uma notificação no próprio celular, no canal
"Mensagens para a pulseira". O `lab_log` registra quem a mandou, nunca o texto.

## Pulseira via Gadgetbridge

O Casca não faz a autenticação própria da Band 7 (Huami2021). Quem conduz a
pulseira é o Gadgetbridge, com a chave obtida na seção abaixo, e ele encaminha
ao pulso as notificações dos apps permitidos. Preparação, uma vez:

1. Instale o Gadgetbridge (F-Droid), force a parada do Zepp Life e adicione a
   pulseira no Gadgetbridge com `0x` seguido da chave.
2. No Gadgetbridge, em Configurações → Notificações, dê acesso às notificações,
   permita o **Casca de Bala** na lista de apps e envie também com a tela
   ligada.
3. Teste: `band_notify {"title": "Casca", "body": "Olá do Casca"}` deve vibrar
   a pulseira e mostrar o texto.

O Zepp Life também encaminha notificações, mas os dois não conduzem a pulseira
ao mesmo tempo. Para voltar ao Zepp Life, desconecte no Gadgetbridge; a chave
não muda.

## Laboratório (experimental)

Ferramentas para a IA explorar e testar Bluetooth pelo celular. O trabalho de
rádio roda em Rust (`crates/casca-ble`, btleplug + JNI); o Java só chama
`BleNative.start` quando o serviço liga.

| Ferramenta | O que faz |
| --- | --- |
| `lab_info` | Aparelho, Android, Bluetooth ligado, permissões e versão do app. |
| `lab_log` | Últimas linhas do log do app (pareamentos, tarefas, tempos). |
| `ble_scan` | Anúncios por 1–15 s: nome, RSSI, serviços, dados de fabricante e de serviço. Precisa de BLUETOOTH_SCAN. |
| `ble_explore` | Conecta e lista serviços, características (propriedades), descritores e o valor das legíveis. |
| `ble_read` | Lê uma característica (UUID curto como `2a19` ou completo). |
| `ble_notify` | Assina uma característica e devolve as notificações de 1–60 s. |
| `ble_write` | Escreve bytes em hex. Só dentro de uma janela de escrita aprovada. |
| `lab_unlock_write` | Pede ao dono, por notificação, para liberar a escrita por 1–60 min. |

Ids de dispositivo são opacos (12 primeiros hex do SHA-256 do endereço), os
mesmos de `band_devices`. Cada chamada conecta e desconecta; as tarefas rodam
em fila, uma por vez. A escrita pode mudar o estado do dispositivo: por isso
exige a aprovação no celular, que vale pelo tempo pedido.

## Chave da pulseira (autenticação Huami)

Recursos além do GATT padrão (vibração e texto controlados, tela, imagem,
miniapps) exigem a chave de 16 bytes que os servidores da Huami associam ao
pareamento da Band com a conta. No app, **Obter chave da pulseira (conta
Zepp)** abre no navegador o login web do próprio Zepp (`user.zepp.com`), onde
o dono entra como entra no Zepp Life: Google, Xiaomi, e-mail ou outro método.
O Google não aceita login dentro de WebView, por isso o navegador. No fim, o
Zepp devolve o navegador a um endereço local (`http://127.0.0.1:<porta>`,
RFC 8252) que o Rust escuta por até 15 minutos. Com o token que vem nesse
retorno, o Rust lista os dispositivos da conta, guarda a chave da Band no
armazenamento privado do app e encerra a sessão. A senha fica com o Zepp ou o
provedor; a chave nunca sai pelo MCP (`lab_info` mostra apenas `huami_keys`, os ids que têm
chave). Não resete a Band: o reset invalida a chave no servidor.

Quem entra no Zepp com Google recebe do Zepp, no retorno ao `127.0.0.1`, um
`huami_auth_code` que o `v2/client/login` não aceita (testado com todos os
grants; o `lab_log` registra as recusas). Para esses casos, a ferramenta MCP
`huami_import` entrega a autorização ao celular, com a mesma aprovação da
escrita (`lab_unlock_write`):

- `auth_key` (32 hex) com `device`: grava a chave daquele dispositivo. A chave
  sai, por exemplo, do método "On a computer" do Gadgetbridge (login com
  Google em `watchface.zepp.com` e um script no console).
- `apptoken` e `userid` de uma sessão do Zepp: o próprio celular lista os
  dispositivos da conta e grava as chaves, sem que elas passem pelo agente.

Nada disso vai para o `lab_log`, que registra só quem importou e o quê.

## Fila de tarefas

O servidor roda em Rust, mas só o app fala Bluetooth. Uma ferramenta que
precisa do aparelho enfileira uma tarefa (`band.devices`, `band.read`); o
`McpService` busca a fila a cada 500 ms (`jobs`), executa e responde
(`complete`). A chamada MCP espera até 10 s (lista) ou 40 s (leitura) e
devolve um erro legível se o celular não responder. Cada dispositivo novo, como
o carro, entra como mais um tipo de tarefa.

## Segurança

- Só aceita conexões de loopback e da tailnet (100.64.0.0/10,
  fd7a:115c:a1e0::/48). Peers da Wi-Fi local são descartados sem resposta.
- Toda chamada a `/mcp` exige um token aprovado no celular; o código
  numérico nas duas telas impede aprovar o pedido de outra pessoa.
- Requisições com cabeçalho `Origin` são recusadas: clientes MCP não são
  navegadores, e isso fecha o ataque de DNS rebinding contra `localhost`.
- O servidor só existe enquanto o serviço está ligado, com notificação fixa.

## Acesso de fora da tailnet

IAs na nuvem (Claude.ai, ChatGPT) chegam pelo Tailscale Funnel rodando no
Termux do mesmo celular, apontando para `localhost:8765`. Antes de abrir o
Funnel, o servidor precisa falar OAuth (descoberta, registro dinâmico,
`/authorize` com aprovação no celular, `/token` com PKCE): é a próxima etapa.

## Desenvolvimento sem celular

```sh
just mcp-dev        # servidor no host, porta 8765, respostas sintéticas
```

Os pedidos de pareamento aparecem no terminal; responda `y <id>` ou `n <id>`.
As tarefas de dispositivo recebem respostas sintéticas no lugar do app.
O código é o mesmo do APK: crate `casca-mcp` (std + serde_json, sem runtime
assíncrono), exposto ao app pela função `b7_mcp_json` da C ABI.
