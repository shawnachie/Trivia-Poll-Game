import javax.swing.JOptionPane;

/**
 * Test class to start the client with a user-provided ID
 */
public class ClientWindowTest {
    public static void main(String[] args) {
        try {
            // Prompt for client ID
            String clientId = JOptionPane.showInputDialog(
                null, 
                "Enter your client ID:", 
                "Trivia Game", 
                JOptionPane.QUESTION_MESSAGE
            );
            
            // Exit if cancelled
            if (clientId == null || clientId.trim().isEmpty()) {
                System.exit(0);
            }
            
            // Prompt for server address
            String serverAddress = JOptionPane.showInputDialog(
                null,
                "Enter server address (leave blank for localhost):",
                "Trivia Game",
                JOptionPane.QUESTION_MESSAGE
            );
            
            // Start client with provided ID and server address
            System.out.println("Starting client with ID: " + clientId.trim() + 
                             " connecting to: " + (serverAddress != null ? serverAddress.trim() : "localhost"));
            new TriviaClient(clientId.trim(), serverAddress != null ? serverAddress.trim() : null);
            
        } catch (Exception e) {
            // Catch any unexpected errors and show a dialog
            JOptionPane.showMessageDialog(
                null, 
                "Error starting client: " + e.getMessage(), 
                "Error", 
                JOptionPane.ERROR_MESSAGE
            );
            e.printStackTrace();
        }
    }
}