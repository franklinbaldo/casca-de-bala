# Miniapp API1: preparação e empacotamento experimental

Fonte JS e inspetor offline incluídos. ZMake é ferramenta GPL-3.0-or-later externa,
fixada em **47acece62528a86a1445d92414cd9a087037eb1c**, não vendorizada:
https://github.com/melianmiko/zmake/tree/47acece62528a86a1445d92414cd9a087037eb1c

O perfil laboratorial internacional l66w_2/deviceSource263 e appId de teste não
foram validados na unidade. Product ID103 do DIS não é esse target. Nenhuma
instalação é permitida por sucesso do inspector: saída mantém installable:false.
O arquivo build-receipt.json preserva um candidato histórico, não certifica o
estado desta cópia nem compatibilidade física. Esta distribuição não inclui BIN.

Requisitos externos: Python3.12, uv, Pillow12.3.0 e qrcode7.4.2, com transitivas
em uv.lock. Provisione conscientemente ferramentas/dependências de suas fontes
oficiais, sem scripts de instalação implícitos. Não aceitar termos em silêncio.
A fonte do formato está em zmake/project_build.py, data/app_a.json e
zmake/zmake.json no commit acima; recursos runtime/JS injetados têm proveniência
ZMake. As opções de compat/esbuild ficam desativadas na receita atual.

Na raiz deste projeto:

```sh
uv run --no-project --python 3.12 python -m unittest discover -s miniapp/packaging -v
uv run --project miniapp/packaging --frozen python miniapp/packaging/package.py prepare miniapp
```

Build opcional, após instalar somente dependências aprovadas e obter o checkout
exato ZMake em um diretório externo escolhido por você:

```sh
export ZMAKE_DIR=/caminho/zmake-fixado
export BUILD_A=/caminho/saida-a
export BUILD_B=/caminho/saida-b
uv sync --project miniapp/packaging --frozen --no-install-project --python 3.12
uv run --project miniapp/packaging --frozen python miniapp/packaging/build.py --zmake "$ZMAKE_DIR" --output "$BUILD_A" --lab-profile
uv run --project miniapp/packaging --frozen python miniapp/packaging/build.py --zmake "$ZMAKE_DIR" --output "$BUILD_B" --lab-profile
cmp "$BUILD_A/band7-lab.candidate.bin" "$BUILD_B/band7-lab.candidate.bin"
B7_BUILD="$BUILD_A/band7-lab/build" node --test miniapp/packaging/runtime.test.mjs
```

O BIN real do builder tem entradas/payload para dispositivo, distinto do ZIP
externo do instalador. build.py normaliza metadata ZIP e fixa packageInfo.time;
não é assinatura, instalação ou teste de API1 física. Inspector limita tamanho,
expansão, nomes, manifesto, assets e entrypoints; testes negativos são sintéticos.
O mock runtime testa lifecycle, não BLE independente ou framebuffer.
