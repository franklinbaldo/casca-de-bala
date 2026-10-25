# Contrato offline v1 (draft revisado)

Contrato local do gateway Android, **não protocolo BLE da Xiaomi**. Sem UUIDs,
opcodes, autenticação BLE, permissões Android ou framing inventados. A revisão
muda o draft inicial; não há cliente implantado ou compatibilidade prometida com
a variante antiga `DeviceOperation(&str)`.

## Interfaces e correlação

A interface humana (sem argumento CLI) mantém `capabilities` e
`validate-card <texto>`. Nomes de dispositivo sem parâmetros retornam
`parameters_required`: seus parâmetros são fornecidos pelo modo `--json`.
Não há expansão de shell, leitura de pacote ou acesso à rede.

`--json` recebe um documento por processo, até 8192 bytes incluindo whitespace:

```json
{"version":1,"id":42,"command":{"op":"miniapps.list","target":{"device_ref":"test-band","model":"xiaomi_smart_band7","firmware":"2.0.0.2","hardware":"0.91.177.3"}}}
```

Resposta correspondente:

```json
{"version":1,"id":42,"mode":"offline","status":"unsupported","operation":"miniapps.list","reason":"adapter_not_implemented"}
```

IDs são inteiros u64 não zero. Clientes JavaScript devem usar codec que preserve
inteiros de 64 bits ou limitar seus próprios IDs à faixa inteira segura.
Resposta sempre usa versão 1. Versão não suportada, ID zero e erro de comando
preservam o ID de um cabeçalho sintaticamente válido. Cabeçalho malformado,
campos ausentes/extras/duplicados, codificação inválida ou excesso de bytes
retornam `id:null`. Um comando desconhecido ou malformado com cabeçalho válido
retorna erro correlacionado. Não se extrai ID por regex de JSON inválido.
Serde desserializa cabeçalho e comando estritamente, preservando o JSON bruto
do comando para detectar campos duplicados. Não aceita documentos concatenados.

Status: `capabilities`, `validated_card`, `unsupported`, `error` (com `code`).
Códigos de processo: 0 sucesso offline, 1 erro, 2 unsupported. CLI humana usa
ID 1 para comando decodificado e `null` para falha de leitura/tamanho/UTF-8.
Ambas as interfaces emitem JSON pelo mesmo serializador. Não ecoam texto,
pacotes, autorização ou identificadores do dispositivo em erros.

CLI humana: máximo 1024 bytes brutos; remove uma terminação LF ou CRLF.
CR isolado e múltiplos comandos são inválidos. Cartão: 1–512 bytes UTF-8,
não apenas espaços, sem caracteres de controle. CLI espera EOF; não é REPL
persistente e stdin aberto pode aguardar indefinidamente. Não há fila, timeout
BLE, deduplicação ou garantia de execução única; ID serve para correlação.

## Parâmetros tipados

Todas as operações de dispositivo exigem `target`: referência local opaca
`device_ref`, `model: xiaomi_smart_band7`, firmware e hardware conhecidos.
Pro/8/outros modelos são rejeitados. Isso valida a declaração, não identifica
fisicamente uma unidade. Nunca inserir endereço Bluetooth pessoal nos exemplos.

`package`: `artifact_ref`, `version`, SHA-256 em 64 hexadecimais minúsculos,
`size_bytes` entre 1 e 67108864. O digest é metadado declarado: não houve leitura,
hash, verificação de assinatura ou compatibilidade do pacote. Referências,
versões e IDs de artefato: 1–64 caracteres ASCII alfanuméricos ou `._-`, sem
caminhos/URLs. São identificadores locais, não IDs wire da Xiaomi.

| Comandos | Classe | Parâmetros adicionais ao target |
| --- | --- | --- |
| `miniapps.list`, `watchfaces.list` | Leitura | Nenhum |
| `miniapps.install`, `watchfaces.install`, `interface.image` | Mutação | package, authorization_ref |
| `miniapps.uninstall`, `watchfaces.uninstall` | Mutação | artifact_id, authorization_ref |
| `resources.install` | Mutação | package, kind (`resources` ou `font`), authorization_ref |
| `firmware.official`, `firmware.native`, `band.exec` | Firmware/execução privilegiada | package, recovery_ref, authorization_ref |
| `sensors.read` | Leitura | sensor (`heart_rate`, `steps`, `spo2`) |
| `interface.show` | Mutação | text, authorization_ref |
| `interface.events` | Leitura | after_sequence (u64), limit (1–100) |
| `interface.vibrate` | Mutação | duration_ms (1–1000), authorization_ref |

Enums Rust distinguem cada operação; payloads compartilhados não misturam suas
identidades. Os limites são políticas conservadoras do host, não limites físicos
comprovados da Band. `band.exec` descreve intenção de executar um artefato nativo,
não shell/Bash ou execução disponível. Sensores enumerados não são capacidades
implementadas. Somente `capabilities` e `validate-card` são anunciados.

## Autorização e ordem de validação

Ordem: limite/decodificação → versão/ID → campos/semântica → autorização → dispatch.
Chamadores Rust também passam pelas validações semânticas. A interface pública
`Authorizer` é política confiável fornecida pelo host, nunca desserializada.
Ela recebe a requisição inteira e deve vincular aprovação externa à operação,
alvo/modelo/versões, payload/digest, usuário/sessão, validade temporal e estado de
recuperação aplicável. Uma referência de recuperação não comprova restauração.
Nenhum verificador de aprovação real foi implementado nesta etapa.

`dispatch` e CLI usam política deny-all para mutações/firmware. JSON com
`authorization_ref` sozinho retorna `authorization_required`. Testes podem
injetar política de aprovação exata para verificar o próximo gate: mesmo
aprovado, toda operação de dispositivo retorna `unsupported`. Nenhuma política
consegue ativar BLE inexistente. Leitura também exigirá sessão/dispositivo e
permissões de privacidade no adaptador real; sua classificação não concede
acesso a dados de saúde.

Antes de implementar adaptador: recuperação e autorização aplicáveis, resolução
local dos artefatos, integridade/autenticidade/compatibilidade, prazo/cancelamento,
sessão e reconciliação de resultado incerto. Timeout mutante não admite retry
cego. Inspeção offline futura deve limitar expansão de arquivos e rejeitar
travessia de diretórios. Simulação deve declarar `simulated`.

Proteção adicional requerida antes de habilitar gestão real: negar remoção de
apps de sistema/desconhecidos e permitir apenas artefatos próprios reconciliados
na mesma unidade. O vínculo deve vir do estado confiável do host, não do JSON.
Ver [critérios e sequência de ensaios](roadmap.md). Essa política de propriedade
ainda não está implementada; deny-all permanece ativo.
