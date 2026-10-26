# Servidor MCP no celular

O Casca é o hub entre os seus dispositivos e as suas IAs. O próprio celular
serve MCP (Streamable HTTP, respostas JSON); não há servidor intermediário.

## Ligar e parear

1. No app, toque em **Ligar servidor MCP**. Ele pede Dispositivos próximos e
   notificações, e mostra o endereço da tailnet (`http://100.x.y.z:8765`).
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

## Cliente de diagnóstico no Jatobá

O repositório também contém `scripts/casca_mcp_client.py`, um cliente mínimo
Python (somente biblioteca padrão) para testar `initialize`, `tools/list` e
`tools/call`, sem colocar o Bearer na linha de comando, em logs ou no chat.
Ele não é um servidor OAuth nem publica o MCP na internet.

Na máquina que chega ao celular pela tailnet, usando o IP mostrado no app:

```sh
python3 scripts/casca_mcp_client.py --url http://100.x.y.z:8765 pair
# Confira no telefone o código que aparecer e toque em Permitir.
python3 scripts/casca_mcp_client.py --url http://100.x.y.z:8765 doctor
python3 scripts/casca_mcp_client.py --url http://100.x.y.z:8765 tools
python3 scripts/casca_mcp_client.py --url http://100.x.y.z:8765 capabilities
python3 scripts/casca_mcp_client.py --url http://100.x.y.z:8765 snapshot
```

Se o pareamento **já foi aprovado** e a resposta foi capturada em um arquivo
privado com `CASCA_TOKEN=`, não crie mais um cliente: importe essa resposta
localmente, usando `import-pair /caminho/privado/response.txt` após
`--url http://100.x.y.z:8765`. O arquivo de origem precisa ter modo `0600`.
O cliente guarda a credencial em
`$XDG_CONFIG_HOME/casca-de-bala/jatoba.json` (ou
`~/.config/casca-de-bala/jatoba.json`), com modo `0600`; o diretório é
`0700`. Nunca versione o arquivo, copie o token para o chat ou compartilhe
logs que o contenham. Apague o arquivo local e revogue o cliente no app caso
o computador deixe de ser confiável.

O cliente recusa qualquer destino que não seja um IP numérico de loopback
ou Tailscale (IPv4 `100.64.0.0/10`, IPv6 `fd7a:115c:a1e0::/48`).
O teste `doctor` só comprova o transporte e a descoberta das ferramentas;
não demonstra autenticação proprietária com a pulseira, leitura BLE ao vivo
ou integração direta do ChatGPT hospedado com a tailnet. Essa última etapa
ainda requer uma ponte com autenticação e transporte adequados, sem expor o
servidor privado.

Teste sintético reproduzível, sem celular:

```sh
python3 -m unittest discover -s ci -p test_casca_mcp_client.py
```

## Ferramentas

| Ferramenta | O que faz |
| --- | --- |
| `casca_capabilities` | Lista os dispositivos e o que cada um oferece agora. |
| `band_snapshot` | Última leitura da Band 7 feita no app: bateria, revisões, PnP e horário. |

`band_snapshot` é cache: o app não lê a pulseira em segundo plano. Para
atualizar, abra o Casca e leia. Leitura sob demanda pela IA é o próximo passo.

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
just mcp-dev        # servidor no host, porta 8765, snapshot sintético
```

Os pedidos de pareamento aparecem no terminal; responda `y <id>` ou `n <id>`.
O código é o mesmo do APK: crate `casca-mcp` (std + serde_json, sem runtime
assíncrono), exposto ao app pela função `b7_mcp_json` da C ABI.
