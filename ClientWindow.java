import java.awt.Color;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import javax.swing.*;

public class ClientWindow implements ActionListener
{
	private JButton poll;
	private JButton submit;
	private JRadioButton options[];
	private ButtonGroup optionGroup;
	private JLabel question;
	private JLabel timer;
	private JLabel score;
	
	private JFrame window;
	
	// Reference to the client that handles network communication
	private TriviaClient client;
	
	// Tracks which option is selected
	private int selectedOption = -1;
	
	// Timer implementation using Swing Timer instead of util.Timer
	private Timer pollTimer;  // Timer for polling phase
	private Timer answerTimer; // Separate timer for answer phase
	private int currentTime;
	private boolean timerExpired = false;
	private boolean isPollPhase = false;
	
	public ClientWindow()
	{
		JOptionPane.showMessageDialog(null, "This is a trivia game");
		
		window = new JFrame("Trivia");
		question = new JLabel("Waiting for game to start..."); // Initial message
		window.add(question);
		question.setBounds(10, 5, 350, 100);
		
		options = new JRadioButton[4];
		optionGroup = new ButtonGroup();
		for(int index=0; index<options.length; index++)
		{
			final int optionIndex = index; // Create final copy for use in actionListener
			options[index] = new JRadioButton("Option " + (index+1));
			options[index].addActionListener(e -> {
				selectedOption = optionIndex;
				System.out.println("Selected option: " + selectedOption);
			});
			options[index].setBounds(10, 110+(index*20), 350, 20);
			window.add(options[index]);
			optionGroup.add(options[index]);
			options[index].setEnabled(false); // Initially disabled
		}

		timer = new JLabel("TIMER");
		timer.setBounds(250, 250, 100, 20);
		window.add(timer);
		
		score = new JLabel("Score: 0");
		score.setBounds(50, 250, 100, 20);
		window.add(score);

		poll = new JButton("Poll");
		poll.setBounds(10, 300, 100, 20);
		poll.addActionListener(this);
		poll.setEnabled(false); // Initially disabled
		window.add(poll);
		
		submit = new JButton("Submit");
		submit.setBounds(200, 300, 100, 20);
		submit.addActionListener(this);
		submit.setEnabled(false); // Initially disabled
		window.add(submit);
		
		// Initialize two separate timers - one for each phase
		// Poll timer
		pollTimer = new Timer(1000, e -> {
			updateTimer();
			if (currentTime <= 0) {
				pollTimer.stop();
				handleTimerExpiration();
			}
		});
		pollTimer.setRepeats(true);
		pollTimer.setCoalesce(true);
		
		// Answer timer
		answerTimer = new Timer(1000, e -> {
			updateTimer();
			if (currentTime <= 0) {
				answerTimer.stop();
				handleTimerExpiration();
			}
		});
		answerTimer.setRepeats(true);
		answerTimer.setCoalesce(true);
		
		window.setSize(400,400);
		window.setBounds(50, 50, 400, 400);
		window.setLayout(null);
		window.setVisible(true);
		window.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		window.setResizable(false);
	}

	/**
	 * Update the timer display
	 */
	private void updateTimer() {
		currentTime--;
		
		// Update timer display
		if (currentTime < 0) {
			timer.setText("Expired");
			timer.setForeground(Color.RED);
		} else {
			timer.setText(Integer.toString(currentTime));
			
			// Change color to red for last 5 seconds
			if (currentTime <= 5) {
				timer.setForeground(Color.RED);
			} else {
				timer.setForeground(Color.BLACK);
			}
		}
		
		window.repaint();
	}
	
	/**
	 * Handle timer expiration
	 */
	private void handleTimerExpiration() {
		timerExpired = true;
		
		if (isPollPhase) {
			// Poll phase expired
			setPollEnabled(false);
			if (client != null) {
				client.submitAnswer(-1); // -1 indicates timeout
			}
		} else {
			// Answer phase expired
			setSubmitEnabled(false);
			setOptionsEnabled(false);
			if (client != null && submit.isEnabled()) {
				client.submitAnswer(-1); // -1 indicates timeout
			}
		}
	}
	
	/**
	 * Stop all timers safely
	 */
	private void stopAllTimers() {
		if (pollTimer.isRunning()) {
			pollTimer.stop();
		}
		if (answerTimer.isRunning()) {
			answerTimer.stop();
		}
		timerExpired = false;
	}

	@Override
	public void actionPerformed(ActionEvent e)
	{
		String input = e.getActionCommand();
		System.out.println("You clicked " + input);
		
		switch(input)
		{
			case "Poll":
				// Send buzz message to server
				if (client != null) {
					client.sendBuzz();
					poll.setEnabled(false); // Disable poll button after clicking
				}
				break;
			case "Submit":
				// Submit selected answer to server
				System.out.println("Submit clicked. selectedOption = " + selectedOption);
				if (client != null && selectedOption >= 0) {
					// Make sure to stop answer timer
					if (answerTimer.isRunning()) {
						answerTimer.stop();
					}
					
					client.submitAnswer(selectedOption);
					submit.setEnabled(false); // Disable submit button after clicking
					setOptionsEnabled(false); // Disable options after submission
				} else {
					JOptionPane.showMessageDialog(window, "Please select an option first");
				}
				break;
			default:
				// The radio button clicks are now handled with lambda expressions
				// in the constructor to avoid switch case issues
				break;
		}
	}
	
	/**
	 * Updates the question text
	 */
	public void setQuestion(String questionText) {
		question.setText(questionText);
		window.repaint();
	}
	
	/**
	 * Updates an option text
	 */
	public void setOption(int index, String optionText) {
		if (index >= 0 && index < options.length) {
			options[index].setText(optionText);
			window.repaint();
		}
	}
	
	/**
	 * Updates the score display
	 */
	public void setScore(String scoreText) {
		score.setText(scoreText);
		window.repaint();
	}
	
	/**
	 * Enables or disables the poll button
	 */
	public void setPollEnabled(boolean enabled) {
		System.out.println("DEBUG - Poll button enabled: " + enabled);
		poll.setEnabled(enabled);
		window.repaint();
	}
	
	/**
	 * Enables or disables the submit button
	 */
	public void setSubmitEnabled(boolean enabled) {
		submit.setEnabled(enabled);
		window.repaint();
	}
	
	/**
	 * Enables or disables all option radio buttons
	 */
	public void setOptionsEnabled(boolean enabled) {
		for (JRadioButton option : options) {
			option.setEnabled(enabled);
		}
		// Reset selection if disabling
		if (!enabled) {
			optionGroup.clearSelection();
			selectedOption = -1;
		}
		window.repaint();
	}
	
	/**
	 * Sets the client reference
	 */
	public void setClient(TriviaClient client) {
		this.client = client;
	}
	
	/**
	 * Starts a timer with specified duration
	 */
	public void startTimer(int duration) {
		// Stop any existing timers
		stopAllTimers();
		
		// Set up for the new timer
		this.currentTime = duration;
		this.timerExpired = false;
		this.isPollPhase = (duration == 15); // 15 seconds is poll phase
		
		// Update timer display
		timer.setText(Integer.toString(currentTime));
		timer.setForeground(Color.BLACK);
		
		// Start the appropriate timer based on duration
		if (isPollPhase) {
			pollTimer.start();
			System.out.println("Poll timer started with " + duration + " seconds");
		} else {
			answerTimer.start();
			System.out.println("Answer timer started with " + duration + " seconds");
		}
	}
	
	/**
	 * Shows the final results dialog
	 */
	public void showFinalResults(String message) {
		// Stop timers if running
		stopAllTimers();
		
		JOptionPane.showMessageDialog(window, message, "Game Over", JOptionPane.INFORMATION_MESSAGE);
	}
	
	/**
	 * Sets up the GUI for the polling phase
	 * @param enabled Whether the poll button should be enabled
	 */
	private void setPollPhase(boolean enabled) {
		System.out.println("DEBUG - Setting poll phase, enabled: " + enabled);
		SwingUtilities.invokeLater(() -> {
			if (this != null) {
				setPollEnabled(enabled);
				setSubmitEnabled(false);
				setOptionsEnabled(false);
				startTimer(15); // 15 second timer for polling phase
				System.out.println("DEBUG - Poll phase set, timer started with 15 seconds");
			} else {
				System.out.println("DEBUG - Error: GUI is null when setting poll phase");
			}
		});
	}
}