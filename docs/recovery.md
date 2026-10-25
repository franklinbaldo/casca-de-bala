# Investigação de boot e recuperação

Este plano trata de firmware nativo; não bloqueia o desenvolvimento offline
da [rota prioritária com miniapp](roadmap.md).

**Decisão atual: gate de escrita de firmware fechado.** Não há nesta pesquisa prova
reproduzível de boot nativo próprio e restauração da Band 7 comum. Isso não prova
impossibilidade; OTA de pacotes conhecidos não prova aceitação de firmware
arbitrário. Capacidade de secure boot do chip não identifica a configuração da
unidade. Compilar para Cortex-M33 não resolve boot, memória, rádio ou drivers.

| Gate | Evidência necessária para avançar | Parada |
| --- | --- | --- |
| A — identificação sem mutação | Modelo/variante, firmware, hardware, app atual, pareamento e fonte de cada dado; registrar identificadores pessoais somente localmente | Modelo/versão incertos ou acesso não autorizado |
| B — recuperação demonstrável | Mapa de boot/memória e método de recuperação fundamentados; originais e hashes; preservação de calibração/chaves; ensaio de restauração reproduzível em unidade autorizada | Backup sem restauração comprovável; pinout/debug/assinatura desconhecidos |
| C — plano revisável | Unidade alvo, procedimento, riscos, limites e autorização específica para ensaio nativo mínimo e restauração | Nenhuma escrita enquanto B ou autorização estiverem pendentes |
| D — boot mínimo recuperável | Logs de boot próprio e retorno ao original na mesma unidade, com hashes e versões | Resultado incerto, perda de recuperação, aquecimento ou falha de carga; sem reflashes cegos |
| E — drivers e terminal | Testes isolados de display, toque, BLE, energia e reconexão; budgets definidos antes do ensaio | Instabilidade, consumo/latência fora do budget ou recuperação degradada |

Esta entrega não executa flash, root, solda, reset, instalação/desinstalação,
alteração de pareamento, novas credenciais, compra, endpoints ou dados de saúde.
Gerenciamento real de apps/watchfaces também depende de identificação,
backup/recuperação apropriados e autorização; não herda permissão de uma
validação offline.

Boot/recuperação continua investigação documental paralela. A próxima etapa
prioritária é validar contratos e preparar o miniapp mínimo offline, conforme
o roteiro, sem conectar o dispositivo.
Quando autorizado, uma prova com Zepp intermediário deve registrar essa
dependência. Essa prova não comprova boot próprio nem encerra sua investigação.
Não prometer framebuffer, FPS, autonomia ou superioridade de Rust sem medições.
