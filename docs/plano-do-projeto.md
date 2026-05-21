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
    *   Os ZIPs são **embutidos como assets** no APK (`app/src/main/assets/binaries-<arch>.zip`), eliminando a necessidade de download externo.
    *   O app detecta a arquitetura do dispositivo em tempo de execução via `Build.SUPPORTED_ABIS[0]` e extrai o ZIP correto para `filesDir/bin/`.
    *   Os binários são extraídos com permissão de execução (`chmod -R 755 bin/`).
    *   **Tamanho dos assets:** aarch64=17.8MB, arm=14.4MB, x86_64=17.2MB, i686=18.9MB.
*   **Execução dos Binários (LD_LIBRARY_PATH):**
    *   Os binários do Termux são compilados para o caminho `/data/data/com.termux/...`. Para rodá-los no contexto do nosso app, o `ProcessBuilder` **deve** injetar a variável de ambiente `LD_LIBRARY_PATH` apontando para a pasta `filesDir/bin/` do nosso app. Isso faz o dynamic linker do Android carregar as bibliotecas `.so` corretas.
    *   Exemplo de injeção:
        ```kotlin
        processBuilder.environment()["LD_LIBRARY_PATH"] = binDir.absolutePath
        processBuilder.environment()["PATH"] = "${binDir.absolutePath}:${System.getenv("PATH")}"
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
*   Menu lateral (`NavigationView` ou `ModalNavigationDrawer`) contendo a lista de todos os projetos (repositórios) clonados no dispositivo.
*   Botão global para "Adicionar Projeto" (Clone do GitHub).
*   Ao tocar em um projeto, a interface principal carrega o contexto (Dashboard) daquele repositório.

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

### 6.3. Arquivos ZIP de Distribuição
Os `.deb` foram extraídos e reempacotados em arquivos `.zip` por arquitetura, hospedados nas **GitHub Releases** do repositório `Theadrill/Nodedroid-Run`:

| Arquivo ZIP | Arquitetura | Dispositivo alvo |
|-------------|-------------|-----------------|
| `binaries-aarch64.zip` | ARM64 | Celulares físicos modernos (maioria) |
| `binaries-arm.zip` | ARMv7 | Celulares físicos mais antigos |
| `binaries-x86_64.zip` | x86_64 | Emuladores no PC |
| `binaries-i686.zip` | x86 | Emuladores mais antigos |

### 6.4. Conteúdo de Cada jniLibs (arm64-v8a)
Os arquivos ficam em `app/src/main/jniLibs/arm64-v8a/` e são instalados automaticamente pelo Android no `nativeLibraryDir`, que é **sempre executável** (sem restrição noexec):

| Arquivo .so | Origem | Tamanho |
|-------------|--------|---------|
| `libnode.so` | `node` (renomeado) | ~47MB |
| `libc++_shared.so` | `libcxx` | ~1.3MB |
| `libcares.so` | `c-ares` | ~247KB |
| `libcrypto.so` | `openssl` | ~5MB |
| `libffi.so` | `libffi` | ~84KB |
| `libicudata.so` | `icu` | ~31MB |
| `libicui18n.so` | `icu` | ~3.2MB |
| `libicuuc.so` | `icu` | ~1.9MB |
| `libnghttp2.so` | `libnghttp2` | ~156KB |
| `libssl.so` | `openssl` | ~854KB |
| `libuv.so` | `libuv` | ~175KB |
| `libz.so` | `zlib` | ~71KB |
| `libcapi.so` | `nodejs` | ~4KB |
| `liblegacy.so` | `nodejs` | ~121KB |
| `libloader_attic.so` | `nodejs` | ~42KB |

**IMPORTANTE — Aliases versionados:** O jniLibs só aceita nomes `lib*.so` sem sufixo de versão. Mas o `node` binário procura pelos nomes versionados (`libz.so.1`, `libssl.so.3`, `libicuuc.so.78`, etc.). A solução é o `SetupActivity` copiar os arquivos para `filesDir/lib/` com os nomes versionados corretos. O `dlopen()` **não é bloqueado** por `noexec` (apenas `execve()` é).

Mapa de aliases criados em `filesDir/lib/`:
```
libz.so       → libz.so.1
libssl.so     → libssl.so.3
libcrypto.so  → libcrypto.so.3
libicui18n.so → libicui18n.so.78
libicuuc.so   → libicuuc.so.78
libicudata.so → libicudata.so.78
```

`LD_LIBRARY_PATH = nativeLibraryDir:filesDir/lib`


---

## 7. Arquivos do Projeto — Estado Atual

### 7.1. Estrutura de Arquivos Relevantes
```
Nodedroid-Run/
├── app/
│   ├── build.gradle.kts              ← BuildConfig com CLIENT_ID e CLIENT_SECRET injetados
│   └── src/main/
│       ├── AndroidManifest.xml       ← Permissões, SetupActivity como Launcher, NodeService
│       ├── java/com/example/nodedroidrun/
│       │   ├── SetupActivity.kt      ← Download de binários, extração, permissões, fallback Mock
│       │   ├── NodeService.kt        ← ForegroundService com notificação persistente
│       │   └── MainActivity.kt       ← Tela principal (a ser implementada)
│       └── res/layout/
│           └── activity_setup.xml    ← Layout dark-themed com ProgressBar
├── docs/
│   └── plano-do-projeto.md           ← Este arquivo
├── local.properties                  ← SDK path + credenciais OAuth (NÃO commitar)
└── binaries-temp/                    ← Pasta temporária local (NÃO commitar)
    └── zips/
        ├── binaries-aarch64.zip      ← Publicar no GitHub Releases
        ├── binaries-arm.zip          ← Publicar no GitHub Releases
        ├── binaries-x86_64.zip       ← Publicar no GitHub Releases
        └── binaries-i686.zip         ← Publicar no GitHub Releases
```

### 7.2. Permissões no AndroidManifest.xml
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

---

## 8. Fases de Desenvolvimento e Milestones

### ✅ Fase 1: Fundação do Sistema Base — CONCLUÍDA
*   [x] Setup do projeto Kotlin (minSdk 26, BuildConfig habilitado).
*   [x] Integração segura das credenciais OAuth via `local.properties` → `BuildConfig`.
*   [x] Desenvolvimento da `SetupActivity` extrai os binários **diretamente dos assets** do APK (sem download externo).
*   [x] Detecção automática da arquitetura do dispositivo (`Build.SUPPORTED_ABIS`) e seleção do ZIP correto.
*   [x] Implementação do `NodeService` (ForegroundService) com notificação persistente.
*   [x] Configuração completa do `AndroidManifest.xml`.
*   [x] Build validado: `./gradlew assembleDebug` → `BUILD SUCCESSFUL`.
*   [x] Binários do Node.js v26.1.0 extraídos dos pacotes do Termux, empacotados em 4 ZIPs e embutidos como assets.

### 🔄 Fase 2: Motor de Processos e Terminal — EM ANDAMENTO
*   [x] Criar `ProcessManager.kt` (Singleton) com `LD_LIBRARY_PATH` configurado.
*   [x] Implementar execução de processos com `ProcessBuilder` injetando variáveis de ambiente obrigatórias.
*   [x] Desenvolver UI de terminal simplificada (ScrollView + TextView monospace).
*   [x] Ligar stdout do processo filho à tela do terminal em tempo real.
*   [x] Escrever `server.js` (Hello World na porta 4150) programaticamente em `filesDir`.
*   [x] `MainActivity` implementada com startup sequence passo a passo:
    1. Info do dispositivo (modelo, API, ABI)
    2. Verificação dos binários instalados + lista de .so
    3. Teste de `node --version`
    4. Escrita do `server.js`
    5. Inicialização do servidor
    6. Leitura do stdout em tempo real
*   [ ] **PENDENTE:** Validar que `node --version` retorna resultado correto no dispositivo físico.
*   [ ] **PENDENTE:** Validar que o servidor HTTP responde em `http://localhost:4150`.
*   [ ] **PENDENTE:** Verificar se as libs .so carregam corretamente (sem erro de linker).

### 🔲 Fase 3: Autenticação e Repositórios
*   [ ] Implementar fluxo OAuth2 com GitHub (Custom Tabs + callback URI scheme).
*   [ ] Armazenar Access Token com `EncryptedSharedPreferences`.
*   [ ] Implementar `git clone` com token injetado na URL.
*   [ ] Menu lateral com lista de projetos clonados.

### 🔲 Fase 4: Gerenciamento do Projeto (Dashboard)
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
