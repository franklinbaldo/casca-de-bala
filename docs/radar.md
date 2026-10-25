# Busca visual contínua — 0.5

Deixe a pulseira parada e caminhe com o telefone. Selecione explicitamente a
pulseira já vinculada e abra o radar. Sem criar/remover vínculo, descoberta de
serviços, escrita GATT, notificações ou autenticação Huami.

A imagem circular pulsa somente quando chega RSSI válido; o valor central é a
mediana das últimas até cinco leituras, dentro de seis segundos. Antes de três
leituras há contagem, sem intensidade enganosa. Ausência não vira zero.
A animação não é um alvo: **posição não estimada** fica sempre visível.
Toque no círculo ou em Pausar para terminar. Continuar abre uma conexão nova
por ação explícita. Sair, girar a tela ou revogar CONNECT fecha a sessão;
resultados disponíveis ficam preservados, sem retomada automática/background.

`RadarGatt` usa `BluetoothGatt.readRemoteRssi()` e `onReadRemoteRssi`, com uma
operação em voo e ao menos 1 s após o callback antes da próxima solicitação.
É uma taxa proposta, não uma propriedade medida da Band. Callback ausente por
2,5 s encerra GATT para não atribuir uma resposta tardia à solicitação seguinte.
Conexão tem timeout15 s. Não é preciso clicar para cada janela de seis tentativas.
As últimas oito janelas e doze amostras recentes ficam em memória, sem banco ou
upload. O modo manual anterior mantém os limites2 min/8 pontos para contratos
Survey; janelas contínuas não são reinterpretadas como paradas espaciais.

Mediana e desvio são descritivos; amostras próximas são correlacionadas. Uma
janela útil tem >=3 leituras; comparação exige duas úteis, diferença mínima
4..12 dB conforme dispersão. Mais forte não garante mais perto: orientação,
paredes, corpo e rádio interferem. Nenhum metro, ângulo, probabilidade ou alvo
é inventado. A melhor janela é apenas a maior mediana no histórico visível.

## Objetivo posterior

O radar espacial interativo pedido continua no plano. GPS será opcional, com
idade/accuracy/consentimento, marcador incerto do telefone e limitações indoor.
Referências Wi-Fi/BLE também exigem contrato e permissões por finalidade.
Nenhuma permissão de localização/rede/câmera foi adicionada nesta fatia.
Estimar a pulseira exige observações espaciais suficientes e modelo validado,
com região/ambiguidade; RSSI sozinho não fornece direção. `SurveySession` é
contrato sintético local, sem coleta desses sensores ou UI de mapa pronta.

## Evidência e teste

Testes Java host exercitam transporte sintético: janela automática, uma operação
em voo, limites, callback tardio, timeout, pausa, reentrada/geração e política de
rotação; apresentação testa ausência, aquecimento, tendência, contraste de
paletas claras/escuras e geometria240..480 dp. Compilação/lint não são ART.
Android real: layout/fontes grandes/TalkBack/rotação/interação e RSSI0.5 NOT_RUN.
O relato0.4 de paradas sem valores observados não valida o sensor por completo.

Fonte normativa: https://developer.android.com/reference/android/bluetooth/BluetoothGatt#readRemoteRssi()
Futura precisão GPS: https://developer.android.com/reference/android/location/Location#getAccuracy()

Teste físico proposto: com Band parada e nRF desconectado, abrir Radar, selecionar
a Band e aguardar sem clicar em pontos. Verificar contagem/pulsos/valor, andar
com o telefone e observar janelas/tendência; pode ser inconclusiva. Pausar deve
parar leitura/animação. Não resetar, desparear ou remover Zepp Life. Relatar apenas
se houve valor e feedback; captura é opcional e pode conter dados locais.
