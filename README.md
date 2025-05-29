# Apache James RabbitMQ Extension

A custom extension for **Apache James 3.8.2** that listens to instructions from a **RabbitMQ** message queue and performs dynamic mailbox actions like moving messages between folders or trashing them. Built with **Java 17**, this module provides a seamless bridge between event-driven architectures and James email operations.

## ✨ Features

- 📥 **Listens** for JSON-based instructions from RabbitMQ.
- ✉️ **Moves or trashes messages** in user mailboxes using the Apache James internal API.
- 🔁 **Responds** back with operation status (`success` / `failed`) via RabbitMQ.
- 🔐 Fully injectable and integrated via Guice and the James extension mechanism.

---

## 🧱 Technology Stack

- **Java 17**
- **Apache James 3.8.2 (JPA flavor)**
- **RabbitMQ**
- **Maven**

---

## 📦 Project Structure

### Core Classes

- **`IncomingMessagePayload.java`**  
  Represents the instruction message consumed from RabbitMQ. Contains fields like `action`, `sourceMailboxID`, `sourceMessageID`, etc.

- **`StatusPayload.java`**  
  Represents the result of an action (either `"success"` or `"failed"`) that is sent back to RabbitMQ.

- **`MailboxActionService.java`**  
  Responsible for:
    - Resolving mailbox and message IDs.
    - Performing move/trash operations.
    - (Optional) Creating mailboxes if they don't exist.

- **`RabbitMqIntegrationService.java`**  
  The central coordinator that:
    - Connects to RabbitMQ.
    - Listens for instruction messages.
    - Delegates actions to `MailboxActionService`.
    - Publishes `StatusPayload` results to RabbitMQ.

---

## 🚀 Build & Deploy

### 1. Build the Project

```bash
mvn -Dmaven.test.skip=true clean package
# or
mvn clean package -U  
```
This creates a shaded JAR at target/james-rabbit-extension-1.0-SNAPSHOT-shaded.jar.

### 2. Deploy to Apache James
Copy the JAR to James’s extensions directory:

In your local Apache James, Under folder 
```bash
extensions-jars
```

If you are using docker :
```bash
docker cp target/james-rabbit-extension-1.0-SNAPSHOT-shaded.jar \
  james-stack-james-1:/root/extensions-jars/
```
### 3. Configure James

Place the following files in the **` conf/ `** directory of your James environment (host or container):
- **`rabbitmq.properties `** RabbitMQ connection details
- **`rabbitmq.properties`** – Add the line:
```bash
guice.extension.module=com.integration.james.module.CustomIntegrationModule
```
- **` logback.xml `**  – To enable logs for your integration.

Set the configuration path in your Docker/Local environment:
```bash
JAVA_TOOL_OPTIONS: "-Djames.configuration.path=/root/conf"
```

You will find all the configuration files needed already in the repository, **config** folder.


## 🐇 RabbitMQ Setup

### Queues & Exchanges

Manually configure RabbitMQ (or let your integration declare these):

#### Queues

**`james.instruction`** – Receives instruction messages.

#### Exchanges

**`james.status`** (type: direct)

Bound to: **`james.status.q`**

Routing key: **`status`**


### Sample Instruction Message

`{
"action": "Trash",
"sourceMailboxID": "946512",
"sourceMessageID": "1",
"destinationMailboxID": null,
"hashID": "demo-trash-01"
}`

### Sample Response Message

`{
"hashID": "demo-trash-01",
"status": "success"
}
`

# 🧪 Local Dev & Testing

## Spin Up Docker Stack

`docker compose down
docker compose build --no-cache james
docker compose up -d`

Follow logs:

`docker compose logs -f james`

## Load Test Data

Inside the container:

`james-cli AddDomain demo.local
james-cli AddUser alice@demo.local secret
james-cli CreateMailbox '#private' alice@demo.local INBOX
james-cli CreateMailbox '#private' alice@demo.local Trash
james-cli CreateMailbox '#private' alice@demo.local Archive`

Inject an email:

`cat > /tmp/demo.eml <<'EOF'
Subject: imported by CLI
From: Bob <bob@example.net>
To: Alice <alice@demo.local>
Hello Alice,
This message was injected with the James CLI “ImportEml” command.
Regards,
Bob
EOF`

`james-cli ImportEml '#private' alice@demo.local INBOX /tmp/demo.eml
`

Get Mailbox IDs via WebAdmin

`curl -H "Authorization: Bearer YOUR_ADMIN_TOKEN" \
http://localhost:8000/users/alice%40demo.local/mailboxes
`

Response

`[
{"mailboxName": "INBOX", "mailboxId": "1"},
{"mailboxName": "Archive", "mailboxId": "3"},
{"mailboxName": "Trash", "mailboxId": "2"}
]`


# 🧪 Sending Instructions

## Option 1: RabbitMQ UI

**Go to Queues → james.instruction → Publish message

Paste your JSON in the payload**

## Option 2: CLI

`docker exec -it <rabbit-container-id> rabbitmqadmin \
publish routing_key=james.instruction payload="$(cat <<'JSON'
{
"action": "Trash",
"sourceMailboxID": "946512",
"sourceMessageID": "1",
"destinationMailboxID": null,
"hashID": "demo-trash-01"
}
JSON
)"`


# ✅ Troubleshooting

**Check logs:**

`docker compose logs -f james`

Make sure:

* All .properties files are mounted correctly
* RabbitMQ queues & exchanges are declared
* Mailbox IDs and Message IDs are correct and exist
* Your custom JAR is located under /root/extensions-jars/ in the James container


# 📄 License

MIT – Free to use, modify, and distribute.

# ✍️ Author

**Ben Salem **
** Apache James + Messaging Integrator**



`Let me know if you want this converted to a real markdown file or extended with diagrams, contribution instructions, or API docs.
`
