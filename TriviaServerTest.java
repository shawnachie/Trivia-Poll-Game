/**
 * Test class to start the server
 */
public class TriviaServerTest {
    public static void main(String[] args) {
        try {
            System.out.println("Starting Trivia Server...");
            
            // Create and start server
            TriviaServer server = new TriviaServer();
            server.start();
            
        } catch (Exception e) {
            System.err.println("Error starting server: " + e.getMessage());
            e.printStackTrace();
        }
    }
}