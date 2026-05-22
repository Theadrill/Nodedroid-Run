# Plano de Projeto: Servidor Node.js Nativo para Android

## 1. Visão Geral e Objetivo
O objetivo deste projeto é criar um aplicativo Android **100% nativo (Kotlin)** capaz de atuar como um servidor de hospedagem para múltiplos projetos Node.js. O app embutirá um ambiente de execução (Node.js e Git) e funcionará como um gerenciador de processos e controle de versão, permitindo clonar repositórios do GitHub, instalar dependências (`npm install`), rodar múltiplos servidores simultaneamente e gerenciar versionamento de arquivos.

O design visa altíssimo desempenho, uso do ecossistema Linux/POSIX no armazenamento privado do app para evitar erros de `symlink` e integração fluida com a rede Tailscale já existente no dispositivo.

---

## 2. Decisões Arquiteturais e Tecnologias (Stack)

*   **Plataforma/Linguagem:** Android Nativo (Kotlin). Nada de frameworks híbridos (Flutter, React Native).
*   **SDK Mínimo:** API 26 (Android 8.0 Oreo) — garante compatibilidade com o dispositivo físico reserva do desenvolvedor.
*   **Armazenamento:** Os projetos **devem** ser salvos no armazenamento privado e interno do aplicativo (`Context.getFilesDir()`, geralmente `/data/data/com.example.nodedroidrun/files/`).
    *   *Justificativa:* Este armazenamento usa o sistema `ext4`, que suporta *symlinks* perfeitamente, sendo obrigatório para o funcionamento do comando `npm install` sem quebrar dependências do `.bin`.
*   **Motor de Execução (Core):**
    *   Binários pré-compilados do **Node.js v26.1.0** (extraídos dos pacotes do Termux) para todas as arquiteturas suportadas.
    *   Os `.so` são **embutidos via jniLibs** no APK (`app/src/main/jniLibs/<abi>/`), instalados automaticamente pelo Android no `nativeLibraryDir` (sempre executável, sem restrição `noexec`).
    *   O app detecta a arquitetura do dispositivo em tempo de execução via `Build.SUPPORTED_ABIS[0]` e o Android seleciona a pasta `jniLibs/<abi>/` correta automaticamente.
    *   O `setupActivity` cria **aliases versionados** em `filesDir/lib/` (ex: `libz.so → libz.so.1`, `libssl.so → libssl.so.3`), pois o `jniLibs` só aceita nomes sem sufixo de versão, mas o `node` binário procura pelos nomes versionados.
*   **Execução dos Binários (LD_LIBRARY_PATH):**
    *   Os binários do Termux são compilados para o caminho `/data/data/com.termux/...`. Para rodá-los no contexto do nosso app, o `ProcessBuilder` **deve** injetar a variável de ambiente `LD_LIBRARY_PATH` apontando para `nativeLibraryDir:filesDir/lib` do nosso app. Isso faz o dynamic linker do Android carregar as bibliotecas `.so` corretas.
    *   Exemplo de injeção:
        ```kotlin
        processBuilder.environment()["LD_LIBRARY_PATH"] = "${nativeLibDir}:${filesLibDir}"
        processBuilder.environment()["PATH"] = "${nativeLibDir}:/system/bin"
        ```
*   **Terminal/Shell:** Utilização de uma **View Customizada Kotlin simplificada** (usando `RecyclerView` ou `TextView` rolável), otimizada para capturar e exibir saídas contínuas (`stdout`/`stderr`) de comandos e servidores Node.js, e um campo de input simples para enviar dados/comandos para o `stdin`. Isso evita o uso de JNI e bibliotecas pesadas de terminal do Termux, mantendo o app leve e focado no gerenciamento de logs dos servidores.
*   **Rede:** Delegação total ao **Tailscale**. O app rodará os projetos em `localhost:<porta>` e o SO do Android lidará com o roteamento através do Tailscale. Nenhuma configuração de túnel ou proxy reverso será construída no app no momento.
*   **Autenticação GitHub:** Implementação via **OAuth 2.0** utilizando *Custom Tabs* para login seguro.
*   **Compatibilidade de Pacotes npm:**
    *   Pacotes **100% JavaScript** funcionam sem adaptação.
    *   Pacotes com **código nativo** (que usam `node-gyp`) podem falhar. Alternativa recomendada para SQLite: usar `sql.js` (SQLite compilado para WebAssembly). O app deve detectar falhas do `node-gyp` no terminal e sugerir alternativas.

---

## 3. UI / UX e Layout da Aplicação

A interface deve ser desenhada para desenvolvedores, lembrando o layout produtivo de IDEs.

### 3.1. Navegação Principal (Menu Lateral / Drawer)
*   Menu lateral (`NavigationView` ou `ModalNavigationDrawer`) contendo:
    *   Botão "Login GitHub" (se não autenticado).
    *   Lista de todos os projetos (repositórios) clonados no dispositivo.
*   Botão global "Adicionar Projeto" com duas opções:
    1. **Clonar do GitHub** — via OAuth, com suporte completo a push/pull/commit.
    2. **Clonar via URL HTTP** — sem login, apenas leitura de repositórios públicos.
*   Ao tocar em um projeto, a interface principal carrega o contexto (Dashboard) daquele repositório.
*   **Tela inicial (lista vazia):** exibe o placeholder *"Sua lista de projetos está vazia — adicione um repositório ou clone do GitHub."*

### 3.2. Dashboard do Projeto (Contexto Ativo)
O Dashboard será dividido visualmente em duas áreas principais:

*   **Área Superior: Abas de Terminais (Execução)**
    *   Sistema de abas (`TabLayout` + `ViewPager2`) onde cada aba representa um terminal.
    *   Capacidade de abrir múltiplos terminais independentes para o mesmo projeto (Ex: um para rodar `npm run dev`, outro para `npm run watch`).
    *   Os terminais devem suportar entrada e saída (stdin/stdout) emulando um shell `sh` ou `bash` restrito ao diretório do projeto ativo.
*   **Área Inferior: Gerenciamento do Git (Estilo VSCode)**
    *   Um painel fixo ou aba persistente para controle de versão.
    *   **Barra de Ações:** Botões para `Pull`, `Push`, `Commit`, e um input de texto para a mensagem do commit.
    *   **Lista de Modificações (Source Control):** Uma `RecyclerView` listando os arquivos modificados (Tracked/Untracked/Modified).
    *   **Ações por Arquivo:** Ao lado de cada arquivo na lista, botões para:
        *   Descartar alterações (revert/checkout do arquivo específico).
        *   Fazer *Stage* (git add) do arquivo.
    *   Botão global para "Descartar Todas as Modificações".

---

## 4. Engenharia de Processos e Serviços do Android

### 4.1. Sobrevivência do Processo (Foreground Service)
Para evitar que o Android (Doze Mode) mate os servidores Node.js quando o app for minimizado ou a tela for desligada:
*   O app deve implementar um `ForegroundService` persistente (`NodeService.kt`).
*   O serviço deve exibir uma Notificação Fixa (Ongoing Notification) na barra de status informando "Servidor Node.js Ativo - X Projetos em execução".
*   O `foregroundServiceType` declarado no Manifest é `specialUse` — obrigatório para processos de longa duração não enquadrados nas categorias padrão.
*   *Nota:* O usuário deve desativar "Economia de Energia" e "Restrição de Background" nas configurações do Android para este app.

### 4.2. Gerenciador de Processos (Process Manager) — **Próxima Fase**
*   O app deve atuar como o "pai" dos processos (`java.lang.ProcessBuilder`).
*   Deve existir um Singleton (`ProcessManager`) que mantém referências (`Map<ProjectId, Process>`) dos processos do Node/Terminal ativos.
*   O `ProcessManager` deve interceptar os *streams* (`InputStream` e `ErrorStream`) dos processos filhos e roteá-los para a renderização nas abas corretas da UI.
*   O ciclo de vida dos processos deve estar amarrado ao `ForegroundService`, não à `Activity` principal, para sobreviverem a rotações de tela ou fechamento da interface.
*   **Variáveis de ambiente obrigatórias** em todo `ProcessBuilder` que chamar Node.js ou Git:
    ```kotlin
    val env = processBuilder.environment()
    env["LD_LIBRARY_PATH"] = binDir.absolutePath
    env["PATH"] = "${binDir.absolutePath}:${System.getenv("PATH")}"
    env["HOME"] = filesDir.absolutePath
    env["TMPDIR"] = cacheDir.absolutePath
    ```

---

## 5. Integração com Git e GitHub (OAuth)

### 5.1. Fluxo de Autenticação OAuth e Segurança
1.  O app registra um URI scheme customizado (ex: `nodeapp://oauth/github`).
2.  Inicia um `CustomTabsIntent` direcionando para a rota de autorização do GitHub.
3.  Recebe o *callback* pelo URI scheme.
4.  Troca o *Code* temporário por um *Access Token*.
5.  Armazena o *Token* utilizando a biblioteca `EncryptedSharedPreferences` (Segurança).
6.  **Segurança das Credenciais:** O `Client ID` e o `Client Secret` do GitHub são configurados localmente no arquivo `local.properties` (excluído do `.gitignore`) de cada ambiente de desenvolvimento e injetados via `BuildConfig` no Gradle.
    *   Cada desenvolvedor gera seu próprio par de credenciais no GitHub (Settings → Developer Settings → OAuth Apps) e configura localmente.
    *   Múltiplas credenciais (uma por máquina de desenvolvimento) são permitidas pelo GitHub e funcionam de forma independente.
    *   **Credenciais Atuais (máquina de desenvolvimento principal):**
        *   `Client ID`: `Ov23liujzHNAWX2gDubW`
        *   Secret: salvo em `OneDrive/Desktop/nodedroid-run-secret.txt` (não commitar).
    *   Callback URL cadastrada no GitHub OAuth App: `nodeapp://oauth/github`

### 5.2. Operações do Git
*   Todas as operações do Git (clone, status, add, commit, push, pull) serão executadas chamando o binário nativo do Git extraído, usando `ProcessBuilder` (ex: `ProcessBuilder(gitPath, "status", "--porcelain")`).
*   O parser do output do comando `git status --porcelain` alimentará a lista de arquivos modificados na interface estilo VSCode.

---

## 6. Binários Nativos — Estratégia de Distribuição

### 6.1. Origem dos Binários
Os binários do Node.js e dependências são obtidos dos **pacotes oficiais do Termux**, que são compilados especificamente para a biblioteca Bionic do Android (diferente dos binários Linux padrão que usam glibc).

> **IMPORTANTE:** Os binários do site oficial `nodejs.org` **NÃO funcionam** no Android, pois são compilados para `glibc` (Linux Desktop). Apenas binários compilados para a Bionic do Android funcionam.

### 6.2. Pacotes Baixados e Processados
Os seguintes pacotes `.deb` foram baixados do repositório `packages.termux.dev` e processados:

| Pacote | Versão | Finalidade |
|--------|--------|------------|
| `nodejs` | 26.1.0-1 | Executável `node` principal |
| `openssl` | 1:3.6.2 | TLS/HTTPS (`libssl.so.3`, `libcrypto.so.3`) |
| `libuv` | 1.52.1 | Event loop (`libuv.so`) |
| `libnghttp2` | 1.69.0 | HTTP/2 (`libnghttp2.so`) |
| `libc++` | 29 | Biblioteca C++ (`libc++_shared.so`) |
| `zlib` | 1.3.2 | Compressão (`libz.so.X`) |
| `git` | 2.54.0 | Binário principal do Git (`libgit.so`) |
| `libcurl` | 8.20.0 | HTTPS (`libcurl.so`, depende de libssh2, libnghttp3, libngtcp2) |
| `libiconv` | 1.18-1 | Conversão de charset (`libiconv.so`) |
| `libssh2` | 1.11.1-1 | SSH para libcurl (`libssh2.so`) |
| `libnghttp3` | 1.15.0 | HTTP/3 para libcurl (`libnghttp3.so`) |
| `libngtcp2` | 1.22.1 | QUIC + crypto OSSL para libcurl (`libngtcp2.so`, `libngtcp2_crypto_ossl.so`) |
| `pcre2` | 10.47 | Regex (`libpcre2-8.so`) |
| `libexpat` | 2.8.1 | XML parsing — git-http-push (`libexpat.so`) | |

### 6.3. Distribuição via jniLibs (Direto no APK)
Os `.so` extraídos dos `.deb` são colocados diretamente em `app/src/main/jniLibs/<abi>/`. O Android empacota e instala automaticamente no `nativeLibraryDir` do app. Não há download externo — tudo vem dentro do APK.

Para cada arquitetura suportada, existe uma pasta em `jniLibs/`:

| Pasta | Arquitetura | Dispositivo alvo |
|-------|-------------|-----------------|
| `arm64-v8a/` | ARM64 | Celulares físicos modernos (maioria) |
| `armeabi-v7a/` | ARMv7 | Celulares físicos mais antigos |
| `x86_64/` | x86_64 | Emuladores no PC |
| `x86/` | x86 | Emuladores mais antigos |

> Os arquivos `.zip` em `binaries-temp/zips/` são mantidos como referência para publicação futura em GitHub Releases, mas **não são usados no build** — o empacotamento é feito via `jniLibs`.

### 6.4. Conteúdo de Cada jniLibs (arm64-v8a)
Os arquivos ficam em `app/src/main/jniLibs/arm64-v8a/` e são instalados automaticamente pelo Android no `nativeLibraryDir`, que é **sempre executável** (sem restrição noexec):

**Node.js e dependências:**
| Arquivo .so | Origem | Tamanho |
|-------------|--------|---------|
| `libnode.so` | `node` (renomeado) | ~47MB |
| `libc++_shared.so` | `libcxx` | ~1.3MB |
| `libcares.so` | `c-ares` | ~247KB |
| `libcrypto.so` | `openssl` | ~5MB |
| `libffi.so` | `libffi` | ~84KB |
| `libicudata.so` | `icu` | ~31MB |
| `libicui18n.so` | `icu` | ~3.2MB |
| `libicutu.so` | `icu` | ~224KB |
| `libicuuc.so` | `icu` | ~1.9MB |
| `libnghttp2.so` | `libnghttp2` | ~156KB |
| `libssl.so` | `openssl` | ~854KB |
| `libuv.so` | `libuv` | ~175KB |
| `libz.so` | `zlib` | ~71KB |
| `libcapi.so` | `nodejs` | ~4KB |
| `liblegacy.so` | `nodejs` | ~121KB |
| `libloader_attic.so` | `nodejs` | ~42KB |
| `libsqlite3.so` | `sqlite` | ~1.2MB |
| `libsqlite3.53.1.so` | `sqlite` | ~1.2MB |

**Git e dependências:**
| Arquivo .so | Origem | Tamanho |
|-------------|--------|---------|
| `libgit.so` | `git` (renomeado) | ~3.4MB |
| `libcurl.so` | `libcurl` | ~897KB |
| `libiconv.so` | `libiconv` | ~1.1MB |
| `libssh2.so` | `libssh2` | ~242KB |
| `libnghttp3.so` | `libnghttp3` | ~149KB |
| `libngtcp2.so` | `libngtcp2` | ~311KB |
| `libngtcp2_crypto_ossl.so` | `libngtcp2` | ~42KB |
| `libpcre2-8.so` | `pcre2` | ~478KB |
| `libexpat.so` | `libexpat` | ~138KB |

**IMPORTANTE — Aliases versionados:** O jniLibs só aceita nomes `lib*.so` sem sufixo de versão. Mas o `node` binário procura pelos nomes versionados (`libz.so.1`, `libssl.so.3`, `libicuuc.so.78`, etc.). A solução é o `SetupActivity` copiar os arquivos para `filesDir/lib/` com os nomes versionados corretos. O `dlopen()` **não é bloqueado** por `noexec` (apenas `execve()` é).

Mapa de aliases criados em `filesDir/lib/`:
```
libz.so       → libz.so.1
libssl.so     → libssl.so.3
libcrypto.so  → libcrypto.so.3
libicui18n.so → libicui18n.so.78
libicuuc.so   → libicuuc.so.78
libicudata.so → libicudata.so.78
libsqlite3.so → libsqlite3.so.0
libexpat.so   → libexpat.so.1
```

### 6.5. Estratégia "noexec" — Execução de Binários no filesDir

**Problema:** Em alguns dispositivos Android, o `filesDir` (`/data/data/<pkg>/files/`) é montado com a flag `noexec`, bloqueando `execve()` e resultando em `error=13, Permission denied`.

**Solução — Symlinks para nativeLibraryDir:** Em vez de copiar binários para `filesDir` e executá-los de lá, o `SetupActivity` cria **symlinks** no `filesDir` apontando para `nativeLibraryDir` (`/data/app/<pkg>/lib/<abi>/`), que é **sempre executável**. Quando o kernel resolve o symlink, a verificação de permissão de execução (`execve`) é feita no destino (`nativeLibraryDir`), não no link. O `filesDir` só precisa suportar symlinks e leitura — o ext4 do armazenamento privado do app garante ambos.

**Aplicação:**
| Binário | Symlink em filesDir | Alvo em nativeLibraryDir |
|---|---|---|
| `git` + 149 sub-comandos | `filesDir/git-core/git*` | `nativeLibraryDir/libgit.so` |
| 7 binários especiais (com libcurl) | `filesDir/git-core/git-remote-https` etc. | `nativeLibraryDir/libgit_remote_https.so` etc. |

Fallback: se `Files.createSymbolicLink()` falhar (ex: filesystem sem suporte), o código tenta `copyTo()` + `setExecutable()`.

O `ProcessManager` injeta `GIT_EXEC_PATH=filesDir/git-core/` em todo processo, e `HOME=filesDir` faz o git ler o `.gitconfig` global.

`LD_LIBRARY_PATH = nativeLibraryDir:filesDir/lib`

### 6.6. Estratégia para Scripts (npm)

Scripts shell (`#!/bin/sh`) não podem usar symlinks porque o interpretador (`sh`) precisa ler o arquivo. A solução para npm é invocá-lo via node diretamente: `node filesDir/node_modules/npm/bin/npm-cli.js <args>`, sem shell intermediário.

### 6.7. Comandos Generalizados (runProjectCommand)

O método `runProjectCommand` em `MainActivity.kt` roteia comandos de forma inteligente:

| Prefixo | Ação |
|---------|------|
| `npm ` | `node npm-cli.js <args>` |
| `npx ` | `node npx-cli.js <args>` |
| `node ` | `libnode.so <args>` |
| `git ` | `filesDir/git-core/git <args>` |
| outros | `sh -c` com PATH aprimorado |

O PATH no ambiente inclui `nativeLibraryDir`, `filesDir/git-core` e `workDir/node_modules/.bin`, permitindo que ferramentas locais (`tsx`, `nodemon`, `yarn`, `pnpm`) sejam encontráveis via `sh -c`.

### 6.8. Suporte a Módulos Nativos (node-gyp) — EM IMPLEMENTAÇÃO

Módulos npm com código C/C++ (`node-gyp rebuild`) não compilam no Android porque falta toolchain (gcc, make, python) e o `/usr/bin/env` para resolver shebangs.

**Estratégia:** Extrair os seguintes pacotes do Termux e disponibilizá-los no app:
- `binutils` — as, ld, ar, etc.
- `gcc` (ou `gcc-default`) — compilador C
- `make` — build system
- `python` — necessário pelo node-gyp
- `coreutils` — fornece `/usr/bin/env` e outras utils POSIX

A instalação será opcional (botão "Instalar ambiente completo" no SetupActivity), detectável em tempo de execução. Quando presente, o `PATH` e `LD_LIBRARY_PATH` são expandidos para incluir os novos binários e libs. Um shim `/usr/bin/env` será criado como symlink para resolver shebangs.

O tamanho total estimado é ~200MB adicionais no APK. O usuário optou explicitamente por tamanho em troca de funcionalidade.


---

## 7. Arquivos do Projeto — Estado Atual

### 7.1. Estrutura de Arquivos Relevantes
```
Nodedroid-Run/
├── app/
│   ├── build.gradle.kts              ← BuildConfig com CLIENT_ID e CLIENT_SECRET injetados
│   └── src/main/
│       ├── AndroidManifest.xml       ← Permissões, SetupActivity como Launcher, NodeService
│       ├── assets/
│       │   └── cert.pem              ← Mozilla CA bundle (226KB)
│       ├── jniLibs/
│       │   ├── arm64-v8a/            ← 34 .so (Node.js + Git + deps + 7 git especiais)
│       │   └── x86_64/               ← 26 .so (Node.js + Git + deps + 7 git especiais)
│       ├── java/com/example/nodedroidrun/
│       │   ├── SetupActivity.kt      ← Aliases, valida node+git, symlinks git-core, cert.pem
│       │   ├── NodeService.kt        ← ForegroundService com notificação persistente
│       │   ├── ProcessManager.kt     ← Singleton processos, env vars, ensureGitSymlinks()
│       │   ├── GitHubOAuth.kt        ← OAuth2 GitHub + user name/email
│       │   ├── Project.kt            ← Data class: id, name, path, cloneUrl, source
│       │   ├── ProjectManager.kt     ← Singleton: load/save/add/remove projects.json
│       │   ├── NodedroidJson.kt      ← Data class + load/save nodedroid.json (comandos customizados)
│       │   └── MainActivity.kt       ← Drawer, login, testar git, adicionar/clonar/remover projetos
│       └── res/
│           ├── layout/
│           │   ├── activity_setup.xml
│           │   ├── activity_main.xml
│           │   └── nav_header.xml
│           └── menu/
│               └── drawer_menu.xml   ← Login GitHub, Testar Git, Adicionar Projeto
├── docs/
│   └── plano-do-projeto.md           ← Este arquivo
├── local.properties                  ← SDK path + credenciais OAuth (NÃO commitar)
└── binaries-temp/                    ← Pasta temporária local (NÃO commitar)
    ├── git-debs/                     ← Pacotes .deb do Git e dependências
    └── git-extracted/                ← Conteúdo extraído dos .deb
```

### 7.2. Permissões no AndroidManifest.xml
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

### 7.3. Variáveis de Ambiente Injetadas (ProcessManager)

Todo processo criado pelo `ProcessManager` recebe:

```
LD_LIBRARY_PATH   = nativeLibraryDir:filesDir/lib
PATH              = nativeLibraryDir:filesDir/git-core:workDir/node_modules/.bin:/system/bin
HOME              = filesDir/
TMPDIR            = cacheDir/
NODE_PATH         = filesDir/node_modules
GIT_EXEC_PATH     = filesDir/git-core/
GIT_SSL_CAINFO    = filesDir/tls/cert.pem
CURL_CA_BUNDLE    = filesDir/tls/cert.pem
```

### 7.4. Estrutura do filesDir Após Setup

```
filesDir/
├── .gitconfig         ← user.name/email do OAuth
├── git-core/          ← 156 symlinks → nativeLibraryDir/lib*.so
├── lib/               ← aliases versionados (libz.so.1, libssl.so.3, etc.)
├── tls/
│   └── cert.pem       ← Mozilla CA bundle (226KB, assets → copiado no setup)
├── projects/          ← repositórios clonados
│   ├── <id8>/
│   └── ...
└── projects.json      ← metadados dos projetos

---

## 8. Fases de Desenvolvimento e Milestones

### ✅ Fase 1: Fundação do Sistema Base — CONCLUÍDA
*   [x] Setup do projeto Kotlin (minSdk 26, BuildConfig habilitado).
*   [x] Integração segura das credenciais OAuth via `local.properties` → `BuildConfig`.
*   [x] Desenvolvimento da `SetupActivity` valida `libnode.so` e cria aliases versionados em `filesDir/lib/`.
*   [x] Implementação do `NodeService` (ForegroundService) com notificação persistente.
*   [x] Configuração completa do `AndroidManifest.xml`.
*   [x] Build validado: `./gradlew assembleDebug` → `BUILD SUCCESSFUL`.
*   [x] Binários do Node.js v26.1.0 extraídos dos pacotes do Termux, `.so` alocados em `jniLibs/<abi>/`.

### ✅ Fase 2: Motor de Processos e Terminal — CONCLUÍDA
*   [x] Criar `ProcessManager.kt` (Singleton) com `LD_LIBRARY_PATH` configurado.
*   [x] Implementar execução de processos com `ProcessBuilder` injetando variáveis de ambiente obrigatórias.
*   [x] Desenvolver UI de terminal simplificada (ScrollView + TextView monospace).
*   [x] Ligar stdout do processo filho à tela do terminal em tempo real.
*   [x] Escrever `server.js` (Hello World na porta 4150) programaticamente em `filesDir`.
*   [x] `SetupActivity` implementada com validação completa e invisível ao usuário:
    1. Validação dos binários (.so) + aliases versionados
    2. Teste de `node --version`
    3. Escrita e execução do `server.js`
    4. Checagem da resposta HTTP em `http://localhost:4150`
    5. Finalização do processo do server.js
    6. Se sucesso → navega para `MainActivity`
    7. Se erro → exibe log técnico na tela e trava
*   [x] `MainActivity` implementada como tela principal com menu lateral (botão login GitHub, lista de projetos)
*   [x] Validado em dispositivo físico: `node --version` retorna `v26.1.0`.
*   [x] Validado em dispositivo físico: servidor HTTP responde em `http://localhost:4150`.
*   [x] Validado em dispositivo físico: libs .so carregam sem erro de linker.

### ✅ Fase 3: Autenticação e Repositórios — CONCLUÍDA
*   [x] Implementar fluxo OAuth2 com GitHub (Custom Tabs + callback URI scheme).
*   [x] Armazenar Access Token com `EncryptedSharedPreferences`.
*   [x] Salvar name + email do `/user` para configuração automática do git (`user.name`, `user.email`).
*   [x] Integração do binário Git v2.54.0 (Termux) + 7 binários especiais (git-remote-https/http, etc.) nos jniLibs.
*   [x] Estratégia de symlinks em filesDir/git-core/ → nativeLibraryDir/libgit.so (ou .so específico para binários com libcurl).
*   [x] Bundle `cert.pem` (226KB, Mozilla CA bundle) em assets → copiado para filesDir/tls/ durante setup.
*   [x] `GIT_SSL_CAINFO` e `CURL_CA_BUNDLE` injetados em todos os processos git.
*   [x] Botão "Testar Git" no menu lateral — fluxo completo: login → config git → testar comandos (init, add, commit, log).
*   [x] Botão "Adicionar Projeto" com duas opções:
      1. **Clonar do GitHub** (requer login OAuth) — injeta token na URL, clone com progresso.
      2. **Clonar via URL HTTP** (não requer login) — clone público.
*   [x] Implementar `git clone` com output em tempo real no dialog de progresso.
*   [x] Menu lateral com lista de projetos clonados (dinâmico).
*   [x] Cards de projeto na tela principal com long-press para remover.
*   [x] Persistência dos projetos em `filesDir/projects.json` via `ProjectManager`.
*   [x] Validado em dispositivo físico: Git v2.54.0, clone via HTTPS, test suite completa.

### 🔲 Fase 4: Gerenciamento do Projeto (Dashboard)
*   [x] Botão play no card do projeto → menu popup: "npm install", "npm start", "Gerenciar comandos".
*   [x] "npm install" e "npm start" executam no diretório raiz do projeto com output em dialog.
*   [x] "Gerenciar comandos" → dialog completo: listar, adicionar, editar, excluir comandos.
*   [x] Arquivo `nodedroid.json` no raiz do projeto com comandos customizados persistidos:
    ```json
    {
      "commands": [
        { "label": "Iniciar servidor", "command": "npm start" },
        { "label": "Instalar deps",    "command": "npm install" },
        { "label": "Build",            "command": "npm run build" }
      ]
    }
    ```
    — Se existir, o menu popup carrega do JSON em vez dos defaults hardcoded.
    — Permite que a configuração viaje com o repositório entre dispositivos.
*   [x] Botão "Clonar do GitHub" → listar repositórios do usuário via API (`/user/repos`) para seleção direta, sem precisar colar URL.
*   [x] Integração do npm (módulo + entrypoint extraídos do pacote npm do Termux, `NODE_PATH` redirecionado).
*   [x] Comandos generalizados (node, git, npm, npx) com PATH expandido para node_modules/.bin e git-core.
*   [x] Corrigido: symlinks git-core recriados em toda inicialização (ensureGitSymlinks), resolvendo ENOENT após update do APK.
*   [x] Corrigido: `--scripts-prepend-node-path=true` removido (deprecado no npm v11+).
*   [x] **Integração Termux via TCP:** Comunicação implementada por socket no localhost (127.0.0.1:9876). O Termux atua como servidor de comandos (netcat + bash), o app envia comandos e recebe respostas. Setup automático de nodejs, python3, clang, make, binutils via `pkg install`.
  *   Fluxo do usuário: `termux-setup-storage` → `pkg install busybox` → `nc -lk -p 9876 -e /bin/bash` → app clica "Setup Termux".
  *   [ ] **Automatizar inicialização do servidor TCP:** Futuramente, gerar script de boot no Termux (:boot) ou usar tmux para manter o servidor sempre ativo sem intervenção do usuário.
  *   [ ] **npm install via Termux:** Implementar envio do projeto para o Termux processar `npm install` + `node-gyp rebuild` e retornar `node_modules` compilado.
*   [ ] Abas de terminais independentes por projeto (`TabLayout` + `ViewPager2`).
*   [ ] Painel Git: parsing de `git status --porcelain` para popular `RecyclerView`.
*   [ ] Ações: `git add`, `git commit`, `git push`, `git pull`, revert por arquivo.

### 🔲 Fase 5: Estabilidade e Testes
*   [ ] Testes com processos pesados (`npm install`, Next.js, Express) com tela bloqueada.
*   [ ] Garantir sobrevivência dos processos ao Doze Mode.

---

## 9. Roadmap Futuro (Fora do Escopo Inicial)
*   **Editor de Texto Integrado:** Implementação de um editor Monaco/CodeMirror para permitir edições de código diretamente no app.
*   **Suporte a Git via SSH:** Alternativa ao OAuth para autenticação via chave SSH.
*   **Detecção de Pacotes Nativos Incompatíveis:** O terminal deve detectar falhas do `node-gyp` e sugerir alternativas JavaScript puras (ex: `sql.js` em vez de `sqlite3`).
*   **[ALTERNATIVA] Delegação ao Termux para npm install / node-gyp:**
  *   **Status: IMPLEMENTADO via TCP (21/05/2026).** Comunicação por socket no localhost (127.0.0.1:9876). Netcat + bash no Termux, app conecta via TCP e envia comandos. Setup automático instala nodejs, python3, clang, make. Próximo passo: integrar ao fluxo de `npm install` nos projetos.
  *   Fluxo: App copia projeto para `/sdcard/`, envia comando ao Termux via TCP, Termux executa `npm install` em ambiente Linux real, app recolhe o resultado. Zero patches, zero linker64.

---

## Atualização

- 2026-05-21: Atualização da documentação — anotações e pequenas correções no plano do projeto. Commit e sincronização realizados.
- 2026-05-21: Integração do binário Git v2.54.0 (Termux) + dependências (libcurl, libiconv, libssh2, libnghttp3, libngtcp2, pcre2, libexpat). Estratégia de symlinks em filesDir/git-core/ → nativeLibraryDir/libgit.so para contornar noexec. SetupActivity valida node + git. ProcessManager injeta GIT_EXEC_PATH. Botão "Testar Git" no menu lateral com fluxo de login/config/teste. `.gitconfig` escrito em filesDir com name/email do OAuth. Build e testes locais validados em dispositivo físico.
- 2026-05-21: Fase 3 concluída — 7 binários git especiais (git-remote-https/http/ftp/ftps, git-http-fetch/push, git-imap-send) como .so separados nos jniLibs (linkam com libcurl). Bundle ca-certificates (cert.pem) em assets. GIT_SSL_CAINFO/CURL_CA_BUNDLE em todos os processos. Fluxo "Adicionar Projeto": clonar do GitHub (com token) ou via URL HTTP. Cards de projeto com long-press para remover. Persistência em projects.json. Clone validado em dispositivo físico com HTTPS.
- 2026-05-21: Pré-requisitos de estabilidade — NodeService integrado ao ProcessManager (callback onCountChanged). Notificação persistente mostra contagem de processos ativos. POST_NOTIFICATIONS solicitado em runtime (Android 13+). MainActivity inicia o ForegroundService ao abrir.
- 2026-05-21: Botão play (▶) nos cards de projeto com PopupMenu (npm install, npm start, Gerenciar comandos). Lista de repositórios do GitHub ao clonar (fetchRepos via /user/repos). Corrigido crash #0x101045c (selectableItemBackground via attribute, não drawable).
- 2026-05-21: NodedroidJson.kt — Gerenciar Comandos funcional (adicionar/excluir comandos, salvos em nodedroid.json no diretório do projeto). Comandos generalizados: handlers explícitos para `node`, `git` + PATH expandido (git-core, node_modules/.bin). GIT_EXEC_PATH/GIT_SSL_CAINFO agora injetados em todos os branches do runProjectCommand. Corrigido `--scripts-prepend-node-path` (deprecado no npm v11+).
- 2026-05-21: ensureGitSymlinks() no ProcessManager — recria symlinks do git-core em toda inicialização, resolvendo ENOENT após update do APK (nativeLibraryDir muda entre versões). Chamado de SetupActivity e MainActivity.onCreate.
- 2026-05-21: Integração Termux via TCP — abandonada tentativa de npm install nativo (bugs em linker64, libandroid-support, deduplicate, ELF wrappers). Implementada comunicação TCP no localhost (127.0.0.1:9876) com netcat+bash no Termux. Botão "Setup Termux" instala nodejs, python3, clang, make, binutils, busybox. README documenta passo a passo do usuário. Próximo: integrar ao fluxo de npm install dos projetos.
- 2026-05-21: Limpeza pós-Termux — removida toda a abordagem antiga de npm install nativo: `ToolchainInstaller.kt` (430 linhas), `toolchain-aarch64.zip` (165MB), `libandroid-support.so`, `libnode_gyp_shim.so`, `libnode_script_runner.so`. Removidos patches de `configure.js`/`find-python.js`, lógica de `fixPythonSymlinks`, `createElfWrappers`, `deduplicate`, `ensureNdkLibraries`, `moveModulesFromTmp`, `setupToolchain`. Mantido apenas o necessário para Node.js + Git + npm CLI + Termux TCP. `runProjectCommand` simplificado de ~260 para ~70 linhas. `MainActivity.kt` perdeu ~200 linhas. App ficou mais leve e focado.

### O que deu errado na abordagem nativa (lições aprendidas):

1. **`libandroid-support.so` ausente no Android 14**: O Python 3.13 da toolchain foi compilado com NDK que depende dessa lib, mas o Android 14 removeu-a do sistema. Baixamos do Termux (20KB) e bundlamos, mas era só o começo dos problemas.

2. **`deduplicate` criava symlinks self-referencing**: Bug no cálculo de `relativize` fazia symlinks apontarem para si mesmos (ex: `clang -> clang`), corrompendo os binários. Corrigido, mas a toolchain já estava danificada.

3. **ELF binaries sem permissão de execução**: No Android, `setExecutable(true)` do Java não funciona para arquivos extraídos no storage interno. Binários ELF precisam ser carregados via `/system/bin/linker64`. Criamos wrappers shell script, mas isso gerou wrapper duplo no python (já wrapped pelo patch do configure.js).

4. **Python `*-config` são shell scripts, não ELF**: O patch do `configure.js` aplicava `linker64` também nos scripts `python3.13-config`, quebrando com "bad ELF magic".

5. **Comunicação inter-processo complexa**: Cada problema resolvido revelava outro. A abordagem de rodar C/C++ toolchain dentro do ambiente restrito do Android sem root é extremamente frágil — o Termux resolve tudo isso por ter um ambiente Linux real.

6. **Tentativa frustrada de intents (`com.termux.RUN_COMMAND`)**: O `am startservice` até iniciava o serviço mas o comando não executava. Faltava permissão `com.termux.permission.RUN_COMMAND` e `allow-external-apps=true`. Mesmo configurado, falhou. Só funcionou com TCP puro.
