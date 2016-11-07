package fr.arolla.web;

import fr.arolla.command.RegistrationCommand;
import fr.arolla.core.Event;
import fr.arolla.core.Players;
import fr.arolla.core.Ticker;
import fr.arolla.web.dto.CashHistoriesDto;
import fr.arolla.web.dto.PlayerOfListAllDto;
import fr.arolla.web.dto.PlayerRegistrationDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
public class WebController {

    private final Logger log = LoggerFactory.getLogger(WebController.class);

    private final Players players;
    private final Ticker ticker;
    private final Event.Publisher eventPublisher;

    @Autowired
    public WebController(Players players, Ticker ticker, Event.Publisher eventPublisher) {
        this.players = players;
        this.ticker = ticker;
        this.eventPublisher = eventPublisher;
    }

    @RequestMapping(value = "/sellers", method = RequestMethod.GET)
    public List<PlayerOfListAllDto> listAllPlayers() {
        log.debug("List of players queried");
        return players.all()
                .map(p -> new PlayerOfListAllDto(p.username(), p.cash(), p.isOnline()))
                .collect(Collectors.toList());
    }

    @RequestMapping(value = "/sellers/history", method = RequestMethod.GET)
    public CashHistoriesDto allCashHistories(@RequestParam("chunk") Optional<Integer> sampling) {
        log.debug("Players' history queried (sampling {})", sampling);
        int currentTick = ticker.current();
        return players.sampledCashHistories(sampling.orElse(10))
                .reduce(new CashHistoriesDto(currentTick),
                        (dto, cashHistory) -> dto.append(cashHistory.username(), cashHistory.getCashHistory()),
                        CashHistoriesDto::mergeWith
                );
    }

    @RequestMapping(value = "/seller", method = RequestMethod.POST, consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public void registerPlayerFormForm(PlayerRegistrationDto dto) {
        log.debug("Registering player {}", dto);
        registerPlayer(dto);
    }

    //
    // @RequestBody is required to trigger jackson deserialization...
    // otherwise @JsonCreator in the dto is neither detected nor used
    // from: http://stackoverflow.com/a/26576930
    //
    // *but* it does not work with application/form-url-encoded
    // from: http://stackoverflow.com/a/38252762
    //
    @RequestMapping(value = "/seller", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE)
    public void registerPlayer(@RequestBody PlayerRegistrationDto dto) {
        new RegistrationCommand(players, eventPublisher)
                .withUsername(dto.username)
                .withPassword(dto.password)
                .withUrl(dto.url)
                .execute();
    }

}
