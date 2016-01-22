# log4j-appender
自定义log4j appender

#### Flume tcp log4j apppender

|选项                        		|	默认值	|说明			|
| --------   	| -----  	| ----  	|
|log4j.appender.KINESIS. deliveryStreamName|		|要将数据发布到的流名称。|
|log4j.appender.KINESIS.encoding|	UTF-8	|用于在发送到 Amazon Kinesis 之前将日志消息字符串转换为字节的编码。|
|log4j.appender.KINESIS.maxRetries|	3|	为发布日志消息而调用 Kinesis API 时的最大重试次数。|
|log4j.appender.KINESIS.threadCount	|20|	用于将日志发布到配置的 Kinesis 流的并行线程数量。|
|log4j.appender.KINESIS.bufferSize	|2000|	要保持在内存中的未完成日志消息的最大数量。|
|log4j.appender.KINESIS.shutdownTimeout	|30	|应用程序 JVM 正常退出前用于发送缓冲消息的秒数。|
|log4j.appender.KINESIS.batchSize	|100|	批量发送record的最小数量|


#### Firehose log4j apppender
 - [x] 改进 LaTex 功能