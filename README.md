# Nodedroid-Run

Run and manage your Node.js projects inside Android.

## Pré-requisitos

- **Termux** instalado via [F-Droid](https://f-droid.org/packages/com.termux/) (a versão da Google Play está desatualizada e não funciona)

## Setup inicial do Termux

Execute os comandos abaixo no Termux, **na ordem**:

### 1. Configurar acesso ao armazenamento

```bash
termux-setup-storage
```

Conceda a permissão quando solicitado.

### 2. Instalar o busybox (fornece o netcat)

```bash
pkg update -y && pkg install -y busybox
```

### 3. Iniciar o servidor TCP

```bash
nc -lk -p 9876 -e /bin/bash
```

Este comando inicia um servidor TCP na porta 9876 que permite ao Nodedroid-Run
enviar comandos e receber respostas. **Deixe este terminal aberto.**

> Dica: use uma sessão do tmux para manter o servidor rodando em background:
> ```bash
> pkg install tmux -y
> tmux new -s nodedroid
> nc -lk -p 9876 -e /bin/bash
> # Ctrl+B, D para desanexar
> ```

### 4. No app Nodedroid-Run

Abra o menu lateral e clique em **"Setup Termux"**. O app vai:
- Conectar ao servidor TCP na porta 9876
- Instalar `nodejs`, `python3`, `clang`, `make`, `binutils`
- Verificar as versões instaladas

Após concluído, o ambiente Termux estará pronto para executar `npm install`
com compilação nativa (node-gyp).

## Como funciona

O app se comunica com o Termux via **TCP no localhost (127.0.0.1:9876)**.
O Termux atua como servidor de comandos, executando `npm install` e
`node-gyp rebuild` em um ambiente Linux real, sem as limitações de
execução de binários nativos do Android.

## Desenvolvimento

Projeto Android nativo (Kotlin) com Node.js embutido via binários `arm64`.

### Estrutura

```
app/
├── src/main/
│   ├── assets/           # Binários (toolchain, npm, libs)
│   ├── java/.../         # Código Kotlin
│   ├── jniLibs/          # Bibliotecas nativas (.so)
│   └── res/              # Layouts, menus, recursos
└── build.gradle.kts
```
