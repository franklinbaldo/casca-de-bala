# SQLite para histórico eventual HR6 / SpO₂ normal v2

**Estado:** implementação Rust offline, somente dados sintéticos. Não existe ainda app
Android, armazenamento cifrado de dados pessoais, autenticação BLE, coleta da
Band 7 ou exposição MCP. Esta etapa complementa o banco de Activity8 sem
modificar seu esquema nem converter amostras esparsas em minutos.

## Banco independente e esquema

`src/event_db.rs` usa rusqlite já fixado no Cargo.lock e um **arquivo
separado** do `ActivityDb`. O banco tem `application_id` B7EV e
`user_version` 1. Recusa arquivos SQLite desconhecidos, versões futuras,
caminho vazio, `:memory:` e URI. Não faz migrações destrutivas.
O caminho deve vir exclusivamente do aplicativo hospedeiro e apontar para
armazenamento privado; nunca de parâmetros remotos/MCP.

Chave de unicidade dos eventos:
`(device, firmware, synthetic, category, ts, payload)`.
Categoria é `hr_resting_6`, `hr_maximum_6` ou `spo2_normal_v2`.
O payload é BLOB **original** de 6 ou 65 bytes, inclusive campos desconhecidos.
Dois eventos distintos no mesmo segundo são conservados se diferem no conteúdo;
repetir exatamente os mesmos bytes é idempotente. Não são gerados valores
substitutos para amostras ausentes.

## Ingestão atômica

- A camada chama `decode_heart_rate6` ou `decode_spo2_v2` **antes**
  de abrir a transação: versão, comprimento e timestamps inválidos falham.
- `BEGIN IMMEDIATE` serializa escritores. Todo lote é inserido de uma vez
  ou revertido. Resposta de sucesso só acontece após COMMIT.
- `INSERT ... ON CONFLICT(primary key) DO NOTHING` deduplica exclusivamente
  registros **idênticos**; não mascara falhas gerais do banco.
- Categorias e origens separadas por referência local, firmware e indicador
  de fixture sintética. A consulta tem intervalo [start,end) e teto de 4096 linhas.
- `DELETE` journal, `FULL` synchronous e busy timeout curto; erros
  são tipados, sem divulgar conteúdo de saúde em texto de erros.

**Não existe checkpoint temporal novo para HR/SpO₂.** Esses registros são
eventuais, não possuem periodicidade de um minuto. Promover
`MAX(ts)` a último dado coletado pularia eventos atrasados. Para sincronização
real, o transport deverá definir token de cursor, janela de replay e
persistência transacional do ACK por categoria após evidência do protocolo.
O checkpoint Activity8 continua independente.

## Testes e limitações

`tests/event_db.rs` contém cinco testes para reabrir arquivo SQLite,
isolamento repouso/máxima e firmware, idempotência, dois registros diferentes
no mesmo segundo, retenção de 60 bytes desconhecidos, batch rollback por
falha artificial, rejeição de esquema e bloqueio do escritor.

`cargo test --offline --test event_db`
`cargo test --offline`
`cargo fmt --all -- --check`
`cargo clippy --offline --all-targets -- -D warnings`

Testes Rust deste módulo foram executados no host; ver [validação](validation.md).
Isso não valida ingestão de saúde física ou integração deste banco em ART.

Os bancos SQLite desta etapa **não cifram dados em repouso**. Somente fixtures
sintéticas devem ser usadas. Antes de receber dados de saúde: integrar proteção
de chave/cifra apropriada, backup e retenção, controles por usuário/dispositivo,
isolamento do processo, exclusão segura e política de consentimento. Testes de
reabertura/rollback não são provas contra corte de energia ou corrupção física.

A coleta Android/BLE de saúde não está implementada, e a
disponibilidade física dessas categorias na Band 7 comum ainda é desconhecida.
Este módulo não faz exportação automática por MCP nem desbloqueia comandos
antes marcados `unsupported`.
