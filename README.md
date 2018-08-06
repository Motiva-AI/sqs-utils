# sqs-utils

A library of high level tools for working with AWS SQS queues.

## Usage

Create a worker for consuming an SQS queue:

```
sqs-utils.core> (doc handle-queue)
-------------------------
sqs-utils.core/handle-queue
([sqs-config queue-url handler-fn {:keys [num-handler-threads auto-delete visibility-timeout], :or {num-handler-threads 4, auto-delete true, visibility-timeout 60}, :as opts}] [sqs-config queue-url handler-fn])
  Set up a loop that listens to a queue and process incoming messages.

   Arguments:
   sqs-config  - A map of the following keys, used for interacting with SQS:
      access-key - AWS access key ID
      secret-key - AWS secret access key
      endpoint   - SQS queue endpoint - usually an HTTPS based URL
      region     - AWS region
   queue-url  - URL of the queue
   handler-fn - a function which will be passed the incoming message. If
                auto-delete is false, a second argument will be passed a `done`
                function to call when finished processing.
   opts       - an optional map containing the following keys:
      num-handler-threads - how many threads to run (defaults: 4)

      auto-delete        - boolean, if true, immediately delete the message,
                           if false, forward a `done` function and leave the
                           message intact. (defaults: true)

      visibility-timeout - how long (in seconds) a message can go unacknowledged
                           before delivery is retried. (defaults: 60)

  See http://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-visibility-timeout.html
  for more information about visibility timeout.

  Returns:
  a kill function - call the function to terminate the loop.
=> nil
```

Send messages to a queue:

```
sqs-utils.core> (doc send-message)
-------------------------
sqs-utils.core/send-message
([sqs-config queue-url payload & {:keys [group-id]}])
  Send a message to a standard queue.
=> nil
sqs-utils.core> (doc send-fifo-message)
-------------------------
sqs-utils.core/send-fifo-message
([sqs-config queue-url message-group-id payload])
  Send a message to a FIFO queue. message-group-id is a tag that specifies the
  group that this message belongs to. Messages belonging to the same group are
  guaranteed FIFO.
=> nil
```

There are also some utilities for use in tests - see the `sqs-utils.test-utils` namespace for details.

## License

The MIT License (MIT)

Copyright © 2018 Motiva AI
