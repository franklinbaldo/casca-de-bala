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
| `band_read` | No app antigo: conecta e lê bateria, revisões e PnP agora. No Casca atual: estado e dados de saúde do banco do Gadgetbridge (abaixo). |
| `band_snapshot` | Última leitura feita (pelo app ou por `band_read`), sem conectar. |
| `band_notify` | Mensagem no pulso: `title` (até 64) e `body` (até 512). Vira uma notificação no celular, que o app que conduz a pulseira leva ao pulso com vibração. |

Nenhuma dessas ferramentas pareia, escreve na pulseira ou expõe endereço
Bluetooth. `band_notify` só cria uma notificação no próprio celular, no canal
"Mensagens para a pulseira". O `lab_log` registra quem a mandou, nunca o texto.

## Pulseira via Gadgetbridge

O app Casca é o Gadgetbridge com este servidor MCP (veja `gb/`). Quem conduz a
pulseira é o próprio Gadgetbridge do app, com a autenticação e os protocolos
dele; os jobs `band.*` usam as APIs internas:

| Ferramenta | No Gadgetbridge |
| --- | --- |
| `band_devices` | aparelhos do Gadgetbridge: estado, bateria, firmware (id opaco) |
| `band_unlock_write` | Solicita aprovação no celular para abrir janela de escrita por 1 a 60 minutos (padrão 30); não escreve sozinho. |
| `band_capabilities` | capacidades **efetivamente declaradas** pelo protocolo Zepp OS: grupos de configuração, IDs de serviços, chaves de configuração reconhecidas e versão da API de configuração; campos ainda não instrumentados aparecem como `null` |
| `band_settings` | `action=get` (todos ou uma `key`) e `action=set` (`key`, `value` booleano); somente opções permitidas e declaradas pelo Gadgetbridge. Gravações exigem a aprovação prévia via `band_unlock_write` e pulseira inicializada. |
| `band_lists` | `action=get`, `kind=alarms` ou `kind=reminders`, `device` opcional; lê o banco local do Gadgetbridge fora da thread principal. Alarme existente pode ser atualizado mediante aprovação no celular. |
| `band_read` | estado do aparelho e o que o banco do Gadgetbridge guardou nas últimas `hours` (1-168, padrão 24): passos, FC (mín/méd/máx), sono por estágio, estresse, SpO2, FC de repouso e nota do sono; série por hora até 48 h, por dia acima. Só lê o banco; `band_sync` traz o mais recente |
| `band_notify` | `onNotification`: texto e vibração no pulso |
| `band_find` | `onFindDevice`: a pulseira vibra por `seconds` (1-30, padrão 5) |
| `band_sync` | `onFetchRecordedData`: atividade, FC, sono, SpO2… |

`band_lists` mostra alarmes armazenados (slot, uso, habilitação, horário,
título) e lembretes (identificador próprio, mensagem, data, repetição). O
resultado é um retrato do banco local, não uma confirmação de que a pulseira
está sincronizada; a leitura não preenche slots vazios nem escreve dados. A
edição de alarmes existentes exige band_unlock_write, um slot válido e
pelo menos um dos campos enabled, hour ou minute (horas 0–23, minutos 0–59).
O MCP recusa criar ou apagar alarmes, modificar slots vazios e escrever
lembretes. A operação persiste no banco local e envia a lista completa pelo
onSetAlarms do Gadgetbridge; retorna requested=true, confirmed=false porque
não há confirmação da pulseira. O celular verifica a aprovação novamente
antes de qualquer alteração. Lembretes graváveis terão PR separada.

`band_settings` retorna `observed=false` para chaves ainda desconhecidas. As únicas
chaves graváveis inicialmente são 14 preferências booleanas públicas do serviço
Zepp OS (tela, vibração, monitoramento e avisos, câmera), reconhecidas pelo
`ConfigArg`. A operação `set` exige `band_unlock_write {"minutes": 30}` e
aprovação na notificação do celular. A resposta `requested=true` é **pedido
enfileirado**, não ACK da pulseira (`confirmed=false`). Configurações de
senha, firmware, bloqueio, autenticação e parâmetros de tipos complexos ficam
fora da lista de escrita. Toda gravação é verificada de novo no Java, no
momento do envio.

`band_capabilities` é somente leitura e aceita `device` opcional (id opaco de 12 hex). A captura acontece quando o Gadgetbridge recebe as respostas da pulseira. Após instalar uma versão nova, abra o Casca, reconecte e sincronize para atualizar as declarações. `null` significa **não observado**, não prova de ausência. Não retorna valores de preferências, MAC, senha ou chaves de autenticação.

Preparação, uma vez:

1. Abra **Casca** (a tela do Gadgetbridge) e adicione a pulseira. A Xiaomi Smart
   Band 7 pede a chave de autenticação (`0x` + 32 hex; veja a seção da chave).
   Se ela estiver no Zepp Life ou em outro Gadgetbridge, desconecte lá antes.
2. Abra **Casca MCP** e ligue o servidor.
3. Teste: `band_notify {"title": "Casca", "body": "Olá do Casca"}`.

As ferramentas `ble_*`, `lab_*` e `huami_import` são do app de laboratório
anterior (feature `lab` do `casca-mcp`) e não existem no app publicado.

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

`band_settings {"action":"status"}` consulta, sem selecionar dispositivo, se a janela de escrita aprovada está ativa. A confirmação é provisória: todo `set` verifica novamente a permissão no hub e no celular.

Edição de lembretes existentes: `band_lists {"action":"set","kind":"reminders","id":"ID_DO_GET","message":"texto"}`. Exige janela de escrita aprovada, limita mensagem a 200 caracteres e preserva data/repetição. Use o `id` textual opaco (UUID) devolvido pela leitura `band_lists` de lembretes; não um inteiro. Não cria ou exclui lembretes. A confirmação é apenas da persistência local e do envio ao Gadgetbridge.

## Gateway universal (experimental)

`gb_devices`, `gb_discover`, `gb_inspect` e `gb_invoke` introduzem o catálogo comum do Gadgetbridge. A reflexão enumera métodos públicos de consulta do coordinator como **descoberta somente**, não os executa livremente. O registro inicial de execução autoriza `device.sync` em dispositivos inicializados; chamadas não registradas são recusadas. O retorno `requested=true` não significa ACK do hardware. A expansão de operações exige conversores tipados e política explícita de autorização.
