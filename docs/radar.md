# Radar0.4 por RSSI de conexão — primeiro MVP

Seleção explícita em Já vinculadas ao celular; conexão GATT LE temporária ao
BluetoothDevice escolhido. RadarGatt chama readRemoteRssi e recebe
onReadRemoteRssi. Não usa anúncios como observações de radar, não descobre
serviços de saúde, não escreve ATT/CCCD, não pareia e não autentica Huami.

Fonte normativa Android:
- https://developer.android.com/reference/android/bluetooth/BluetoothGatt#readRemoteRssi()
- https://developer.android.com/reference/android/bluetooth/BluetoothGattCallback#onReadRemoteRssi(android.bluetooth.BluetoothGatt,%20int,%20int)

Para apps Android12+ a API exige CONNECT, já usado pelo leitor básico. O modo
radar lista vinculados sem solicitar SCAN. SCAN/neverForLocation permanece somente
na busca básica de anúncios; nenhuma posição é derivada desses resultados.
Não há GPS, localização/câmera/Wi-Fi, coordenadas ou permissão nova. Rever política
se uma versão futura passar a inferir posições físicas usando resultados de scan.

## Andar → parar → medir

Toque Radar da pulseira, selecione a pulseira vinculada e aguarde conexão.
Pare num lugar e toque Parei aqui: medir ponto. São até6 tentativas seriais, com
intervalo mínimo de1s após callback; fique parado alguns segundos. Ande para
outro lugar e marque outra parada. A tela mostra barras e estatísticas na ordem
das paradas: não é planta da casa, mapa de localização ou coordenada estimada.
Não há ponto medido antes de sua ação; falha sem RSSI aparece explicitamente.

Cada sessão dura no máximo2min e guarda no máximo8 pontos/48 amostras válidas,
em memória. Cada ponto tem janela de até10s e ID manual ordinal. Para cada
amostra válida: hora monotônica do pedido, entrada do callback e processamento
(ms elapsedRealtime), RSSI bruto e origem OUR_APP ou SYNTHETIC na sessão.
A hora da entrada do callback não é instante medido dentro do rádio remoto.
Sem coordenadas e sem inventar posição quando indisponível; timestamps permitem
uma futura associação temporal explícita a outros sensores, ainda não criada.

Mediana e dispersão populacional descrevem a janela, não são confiança espacial.
Amostras correlacionadas não contam como provas independentes. Menos de3 amostras
é baixa quantidade; desvio acima de6dB marca oscilação. São políticas de UI,
não limites do hardware ou calibração física. RSSI validado em -127..126;
127/fora da faixa e status GATT não zero não viram sinal0. Sem leitura: mediana null.

Quente/frio significa apenas sinal mediano mais forte/fraco que o último ponto
válido. São necessárias3 amostras em ambos; diferença mínima é heurística de4dB,
aumentada pela dispersão, limitada a12dB. Sem diferença clara/inconclusivo são
resultados legítimos. Não há modelo de metros/ângulo/probabilidade. Corpo,
orientação e paredes podem mudar o sinal sem mudança de distância. Barras são
escala visual recortada -110..-30dBm, não distância nem coordenadas.

## Lifecycle e erros

Conexão: timeout15s; pedido RSSI:2,5s. Timeout sem callback encerra o GATT inteiro:
a API não fornece ID por request, logo não reutilizamos essa sessão para outro
ponto que pudesse consumir o resultado atrasado. Por sessão há geração e comparação
de objeto BluetoothGatt; callback antigo/duplicado sem pedido pendente é ignorado.
Dentro do mesmo GATT, callbacks não carregam ID e o app depende do contrato de
resposta da plataforma; não promete identificar qualquer duplicata arbitrária.

Read rejeitado/falha/sem RSSI, permissão revogada, Bluetooth desligado,
desconexão, cancelamento e limites preservam os pontos já recebidos. Nenhuma
falha vira indicação de fora de alcance, distância0 ou confirmação de identidade.
Sair/rotacionar encerra a conexão. Na rotação, snapshot imutável sem Context,
BluetoothDevice ou callbacks é retido via nonconfiguration instance e mostrado
como encerrado; não há reconexão automática. Morte de processo perde pontos,
porque não existe persistência. Não há serviço de segundo plano, logs de saúde
ou envio à rede. O leitor básico/snapshot continuam disponíveis na tela inicial.

## Evidência

RadarSessionTest usa somente clock/RSSI/callbacks sintéticos e cobre blocos,
mediana, dispersão, tendências, limites, reentrância, rejeição/falha de leitura,
permissão, callback ausente/tardio/duplicado, geração anterior, clock regressivo,
cancelamento, desconexão e política de interrupção/retensão de resultados.
A ligação RadarGatt/UI foi compilada no APK; ART, rotação e RSSI físicos desta
função seguem NOT_RUN até teste no telefone. Não confundir teste do modelo com
instrumentação Android ou com a captura básica da versão0.3.
