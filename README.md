# Cancelamento de eventos — Arquitetura Hexagonal & Clean

Projeto Java dividido nos módulos `domain`, `application` e `infrastructure`, com API REST, GraphQL e processamento de eventos de domínio via outbox.

## Pré-requisitos

- JDK 17, com `JAVA_HOME` configurado.
- Docker com Docker Compose para executar o MySQL.

O Gradle é executado pelo wrapper incluído no repositório. Execute os comandos abaixo na raiz do projeto.

No macOS, selecione o Java 17 antes de executar o Gradle:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export PATH="$JAVA_HOME/bin:$PATH"
java -version
```

## Subir o projeto

Inicie o banco:

```bash
docker compose up -d mysql
```

Aguarde o MySQL ficar pronto para aceitar conexões. É possível acompanhar a inicialização com `docker compose logs -f mysql` e sair dos logs com `Ctrl+C`.

O Compose cria o banco `events` na porta `3306`, com usuário `root` e senha `root`, conforme a configuração local em `infrastructure/src/main/resources/application.properties`. As tabelas são criadas ou atualizadas pelo Hibernate ao iniciar a aplicação.

Inicie a aplicação:

```bash
./gradlew :infrastructure:bootRun
```

A aplicação fica disponível em `http://localhost:8080`:

- REST: `GET /events/{id}` e `POST /events/{id}/cancel`.
- Consulta pública REST: `GET /events/{id}` com o header `X-Public: true`.
- GraphQL: `POST /graphql`, com as operações `eventOfId(id: ID!)` e `cancelEvent(id: ID!)`.
- Interface GraphiQL: `http://localhost:8080/graphiql`.

Para encerrar, use `Ctrl+C` no terminal da aplicação e execute `docker compose stop mysql` para parar o banco.

## Rodar a suíte de testes

Execute todos os testes unitários e de integração:

```bash
./gradlew test
```

Os testes de domínio e de casos de uso estão nos módulos `domain` e `application`. Os testes de integração estão em `infrastructure` e utilizam o perfil `test` com banco H2 em memória; não é necessário iniciar o MySQL nem a aplicação separadamente.

Para executar cada camada separadamente:

```bash
./gradlew :domain:test :application:test
./gradlew :infrastructure:test
```

Para forçar uma nova execução mesmo sem alterações:

```bash
./gradlew test --rerun-tasks
```

Os relatórios HTML ficam em:

- `domain/build/reports/tests/test/index.html`
- `application/build/reports/tests/test/index.html`
- `infrastructure/build/reports/tests/test/index.html`

A suíte cobre as regras de cancelamento, presenters REST, operações GraphQL, persistência e o fluxo assíncrono. O teste `EventCancellationIntegrationTest` publica o JSON persistido na outbox pelo consumidor real e verifica o cancelamento dos ingressos, o reprocessamento idempotente e a preservação dos ingressos de outro evento.

## Onde acontece a cascata de cancelamento

O `CancelEventUseCase` chama `Event.cancel()`, que registra `EventCancelled` (tipo `event.cancelled`). O `EventDatabaseRepository` persiste a alteração e o evento de domínio na outbox. A cada dois segundos, o `OutboxRelay` publica o JSON via `QueueGateway`; o `ConsumerQueueGateway` o processa assincronamente pelo executor em memória `queueExecutor` e aciona `CancelEventTicketsUseCase`. Esse caso de uso busca os ingressos do evento, chama `Ticket.cancel()` e persiste o estado `CANCELLED` de forma idempotente, sem acoplar os agregados nem cancelar ingressos diretamente no comando de evento. Não é necessário um broker externo.
