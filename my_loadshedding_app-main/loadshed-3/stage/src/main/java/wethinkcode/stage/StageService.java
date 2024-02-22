package wethinkcode.stage;


import com.google.common.annotations.VisibleForTesting;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.plugin.bundled.CorsPluginConfig;
import org.apache.activemq.ActiveMQConnectionFactory;
import wethinkcode.loadshed.common.mq.MQ;
import wethinkcode.loadshed.common.transfer.StageDO;

import javax.jms.*;

/**
 * I provide a REST API that reports the current loadshedding "stage". I provide two endpoints:
 * <dl>
 * <dt>GET /stage
 * <dd>report the current stage of loadshedding as a JSON serialisation of a {@code StageDO} data/transfer object
 * <dt>POST /stage
 * <dd>set a new loadshedding stage/level by POSTing a JSON-serialised {@code StageDO} instance as the body of the
 * request.
 * </ul>
 */
public class StageService
{
    public static final String MQ_URL = "tcp://localhost:61616";

    public static final String MQ_USER = "admin";

    public static final String MQ_PASSWD = "admin";

    public static final int DEFAULT_STAGE = 0; // no loadshedding. Ha!

    public static final int DEFAULT_PORT = 7001;

    public static final String MQ_TOPIC_NAME = "stage";

    private String[] cmdLineMsgs;

    private Connection connection;

    private Session session;

    public static void main( String[] args ){
        final StageService svc = new StageService().initialise();
        svc.start();
    }

    private int loadSheddingStage;

    private Javalin server;

    private int servicePort;

    @VisibleForTesting
    public StageService initialise(){
        return initialise( DEFAULT_STAGE );
    }

    @VisibleForTesting
    StageService initialise( int initialStage ){
        loadSheddingStage = initialStage;
        assert loadSheddingStage >= 0;

        server = initHttpServer();
        return this;
    }

    public void start(){
        start( DEFAULT_PORT );
    }

    @VisibleForTesting
    void start( int networkPort ){
        servicePort = networkPort;
        run();
    }

    public void stop(){
        server.stop();
    }

    public void run(){
        server.start( servicePort );
    }

    private Javalin initHttpServer(){
        return Javalin.create(config -> { config.plugins.enableCors(cors -> {
                    // allow any host to access your resources
                    cors.add(CorsPluginConfig::anyHost);
                });
        })
            .get( "/stage", this::getCurrentStage )
            .post( "/stage", this::setNewStage );
    }

    private Context getCurrentStage( Context ctx ){
        return ctx.json( new StageDO( loadSheddingStage ) );
    }

    private Context setNewStage( Context ctx ){
        final StageDO stageData = ctx.bodyAsClass( StageDO.class );
        final int newStage = stageData.getStage();

        if( newStage >= 0 ){
            loadSheddingStage = newStage;
            broadcastStageChangeEvent( ctx );
            ctx.status( HttpStatus.OK );
        }else{
            ctx.status( HttpStatus.BAD_REQUEST );
        }
        return ctx.json( new StageDO( loadSheddingStage ) );
    }

    private void broadcastStageChangeEvent( Context ctx ){
        final StageDO stageData = ctx.bodyAsClass( StageDO.class );
        final int newStage = stageData.getStage();
        setUpMessageSender(newStage);

    }

    private void setUpMessageSender(int stage){
        try{
            final ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory( MQ_URL );
            connection = factory.createConnection( MQ_USER, MQ_PASSWD );
            connection.start();

            session = connection.createSession( false, Session.AUTO_ACKNOWLEDGE );
            sendMessages( "{ \"stage\": "+stage+ " }" );

        }catch( JMSException erk ){
            System.out.println("Broker down");
        }finally{
            closeResources();
            System.out.println( "Bye..." );
        }
    }
    private void sendMessages( String message ) throws JMSException {
        javax.jms.Topic topic = session.createTopic(MQ_TOPIC_NAME);
        javax.jms.MessageProducer producer = session.createProducer(topic);
        javax.jms.TextMessage textMessage = session.createTextMessage(message);
        producer.send(textMessage);
        System.out.println("Sent message: " + message);
        producer.close();
    }
    private void closeResources(){
        try{
            if( session != null ) session.close();
            if( connection != null ) connection.close();
        }catch( JMSException ex ){
            // wut?
        }
        session = null;
        connection = null;
    }

}
