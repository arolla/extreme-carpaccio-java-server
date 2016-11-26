package fr.arolla.core;

import fr.arolla.util.Randomizator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import rx.Observable;

import java.util.stream.Collectors;

/**
 * @author <a href="http://twitter.com/aloyer">@aloyer</a>
 */
@Service
public class Game {
    private final Logger log = LoggerFactory.getLogger(Game.class);

    private final GameListener listener;
    private final Players players;
    private final QuestionGenerator questionGenerator;
    private final QuestionDispatcher dispatcher;
    private final FeedbackSender feedback;
    private final Randomizator randomizator;

    @Autowired
    public Game(GameListener listener,
                Players players,
                QuestionGenerator questionGenerator,
                QuestionDispatcher dispatcher,
                FeedbackSender feedback, Randomizator randomizator) {
        this.listener = listener;
        this.players = players;
        this.questionGenerator = questionGenerator;
        this.dispatcher = dispatcher;
        this.feedback = feedback;
        this.randomizator = randomizator;
    }

    public void processIteration(int tick) {
        listener.iterationStarting(tick);
        Question q = questionGenerator.nextQuestion(tick, randomizator);

        Observable.from(players.all().collect(Collectors.toList()))
                .flatMap(p -> dispatch(tick, q, p))
                .map(qop -> evaluateAnswer(qop, tick))
                .map(fb -> sendFeedback(fb, tick))
                .subscribe(
                        next -> log.info("qop received and feedbacked for tick {}.", next, tick),
                        error -> log.error("Ooops (during tick {})", tick, error),
                        () -> {
                            log.info("Everything is fine for tick {}.", tick);
                            players.saveState();
                        }
                );
    }

    private Feedback sendFeedback(Feedback fb, int tick) {
        if (fb.hasFeedback()) {
            feedback.notify(tick, fb);
        }
        return fb;
    }

    private Feedback evaluateAnswer(QuestionOfPlayer qop, int tick) {
        boolean online = true;
        Feedback fb = Feedback.NO_FEEDBACK;
        switch (qop.status()) {
            case OK:
                if (qop.isResponseAccepted()) {
                    players.addCash(qop.username(), qop.gainAmount());
                    listener.playerWon(tick, qop.username(), qop.gainAmount());
                    fb = Feedback.winning(qop);
                } else {
                    players.addCash(qop.username(), qop.gainPenalty());
                    listener.playerLost(tick, qop.username(), qop.gainPenalty(), "response-invalid");
                    fb = Feedback.losing(qop);
                }
                break;
            case QuestionRejected:
                if (qop.isInvalidQuestion()) {
                    players.addCash(qop.username(), qop.gainAmount());
                    listener.playerWon(tick, qop.username(), qop.gainAmount());
                    fb = Feedback.winning(qop);
                } else {
                    players.addCash(qop.username(), qop.gainPenalty());
                    listener.playerLost(tick, qop.username(), qop.gainPenalty(), "response-invalid");
                    fb = Feedback.losing(qop);
                }
                break;
            case UnreachablePlayer:
            case Timeout:
            case NoResponseReceived:
                online = false;
                players.addCash(qop.username(), qop.lossOfflinePenalty());
                listener.playerLost(tick, qop.username(), qop.lossOfflinePenalty(), "timeout");
                fb = Feedback.error(qop, qop.lossOfflinePenalty());
                break;
            case InvalidResponse:
                players.addCash(qop.username(), qop.lossOfflinePenalty());
                listener.playerLost(tick, qop.username(), qop.lossOfflinePenalty(), "timeout");
                fb = Feedback.error(qop, qop.lossOfflinePenalty());
                break;
            case Error:
                online = false;
                players.addCash(qop.username(), qop.lossErrorPenalty());
                listener.playerLost(tick, qop.username(), qop.lossErrorPenalty(), "error");
                fb = Feedback.error(qop, qop.lossErrorPenalty());
                break;
            case NotSent:
            default:
                online = false;
                log.warn("Unsupported status {} for player {} (during tick {})", qop.status(), qop.username(), tick);
        }

        markPlayerOnline(tick, qop, online);
        return fb;
    }

    private void markPlayerOnline(int tick, QuestionOfPlayer qop, boolean online) {
        if (online == qop.isPlayerOnline())
            return;
        players.markPlayerOnline(qop.username(), online);
        listener.playerOnline(tick, qop.username(), online);
    }

    private Observable<QuestionOfPlayer> dispatch(int tick, Question q, Player p) {
        listener.dispatchingQuestion(tick, q, p);
        return dispatcher.dispatchQuestion(tick, q, p);
    }
}
