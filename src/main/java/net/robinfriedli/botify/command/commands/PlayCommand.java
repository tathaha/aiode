package net.robinfriedli.botify.command.commands;

import java.util.List;
import java.util.concurrent.Callable;

import com.wrapper.spotify.SpotifyApi;
import com.wrapper.spotify.model_objects.specification.ArtistSimplified;
import com.wrapper.spotify.model_objects.specification.PlaylistSimplified;
import com.wrapper.spotify.model_objects.specification.Track;
import net.dv8tion.jda.core.entities.Guild;
import net.dv8tion.jda.core.entities.Member;
import net.dv8tion.jda.core.entities.MessageChannel;
import net.dv8tion.jda.core.entities.VoiceChannel;
import net.robinfriedli.botify.audio.AudioManager;
import net.robinfriedli.botify.audio.AudioPlayback;
import net.robinfriedli.botify.audio.AudioQueue;
import net.robinfriedli.botify.audio.YouTubePlaylist;
import net.robinfriedli.botify.audio.YouTubeService;
import net.robinfriedli.botify.audio.YouTubeVideo;
import net.robinfriedli.botify.command.AbstractCommand;
import net.robinfriedli.botify.command.ArgumentContribution;
import net.robinfriedli.botify.command.CommandContext;
import net.robinfriedli.botify.command.CommandManager;
import net.robinfriedli.botify.entities.Playlist;
import net.robinfriedli.botify.exceptions.InvalidCommandException;
import net.robinfriedli.botify.exceptions.NoResultsFoundException;
import net.robinfriedli.botify.util.SearchEngine;
import net.robinfriedli.stringlist.StringListImpl;

public class PlayCommand extends AbstractCommand {

    public PlayCommand(CommandContext context, CommandManager commandManager, String commandString, String identifier) {
        super(context, commandManager, commandString, false, false, false, identifier,
            "Resume the paused playback, play the current track in the current queue or play the specified track, " +
                "video or playlist. Can play youtube and spotify tracks or lists and also local playlists. Local playlists, " +
                "like the queue, can contain both youtube videos and spotify tracks.\n" +
                "Usage examples:\n$botify play\n$botify play numb\n$botify play $youtube youtube rewind 2018\n" +
                "$botify play $youtube $list important videos\n$botify play $spotify $list $own goat", Category.PLAYBACK);
    }

    @Override
    public void doRun() throws Exception {
        CommandContext context = getContext();
        Guild guild = context.getGuild();
        Member member = guild.getMember(context.getUser());
        VoiceChannel channel = member.getVoiceState().getChannel();
        AudioManager audioManager = getManager().getAudioManager();
        MessageChannel messageChannel = getContext().getChannel();
        AudioPlayback playbackForGuild = audioManager.getPlaybackForGuild(guild);
        playbackForGuild.setCommunicationChannel(messageChannel);

        if (argumentSet("list")) {
            if (argumentSet("spotify")) {
                playSpotifyList(channel, playbackForGuild);
            } else if (argumentSet("youtube")) {
                playYouTubePlaylist(channel, audioManager, guild, playbackForGuild);
            } else {
                playLocalList(channel, audioManager, guild, playbackForGuild);
            }
        } else {
            if (getCommandBody().isBlank()) {
                if (playbackForGuild.isPaused()) {
                    playbackForGuild.unpause();
                } else if (!audioManager.getQueue(guild).isEmpty()) {
                    audioManager.playTrack(guild, channel);
                } else {
                    throw new InvalidCommandException("Queue is empty. Specify a song you want to play.");
                }
            } else {
                if (argumentSet("youtube")) {
                    playYouTubeVideo(channel, audioManager, guild);
                } else {
                    playSpotifyTrack(channel, audioManager, guild, messageChannel);
                }
            }
        }
    }

    private void playSpotifyTrack(VoiceChannel channel, AudioManager audioManager, Guild guild, MessageChannel messageChannel) throws Exception {
        List<Track> found;
        SpotifyApi spotifyApi = getManager().getSpotifyApi();
        if (argumentSet("own")) {
            found = runWithLogin(getContext().getUser(), () -> SearchEngine.searchOwnTrack(spotifyApi, getCommandBody()));
        } else {
            found = runWithCredentials(() -> SearchEngine.searchTrack(spotifyApi, getCommandBody()));
        }

        if (found.size() == 1) {
            audioManager.getQueue(guild).set(audioManager.createPlayable(!argumentSet("preview"), found.get(0)));
            audioManager.playTrack(guild, channel);
        } else if (found.isEmpty()) {
            sendMessage(messageChannel, "No results found");
        } else {
            askQuestion(found, track -> {
                String artistString = StringListImpl.create(track.getArtists(), ArtistSimplified::getName).toSeparatedString(", ");
                return String.format("%s by %s", track.getName(), artistString);
            }, track -> track.getAlbum().getName());
        }
    }

    private void playYouTubeVideo(VoiceChannel channel, AudioManager audioManager, Guild guild) {
        YouTubeService youTubeService = getManager().getAudioManager().getYouTubeService();
        if (argumentSet("limit")) {
            int limit = getArgumentValue("limit", Integer.class);
            if (!(limit > 0 && limit <= 10)) {
                throw new InvalidCommandException("Limit must be between 1 and 10");
            }

            List<YouTubeVideo> youTubeVideos = youTubeService.searchSeveralVideos(limit, getCommandBody());
            if (youTubeVideos.size() == 1) {
                audioManager.getQueue(guild).set(audioManager.createPlayable(false, youTubeVideos.get(0)));
                audioManager.playTrack(guild, channel);
            } else if (youTubeVideos.isEmpty()) {
                throw new NoResultsFoundException("No YouTube videos found for " + getCommandBody());
            } else {
                askQuestion(youTubeVideos, YouTubeVideo::getTitle);
            }
        } else {
            YouTubeVideo youTubeVideo = youTubeService.searchVideo(getCommandBody());
            audioManager.getQueue(guild).set(audioManager.createPlayable(false, youTubeVideo));
            audioManager.playTrack(guild, channel);
        }
    }

    private void playYouTubePlaylist(VoiceChannel channel, AudioManager audioManager, Guild guild, AudioPlayback audioPlayback) {
        YouTubeService youTubeService = getManager().getAudioManager().getYouTubeService();
        if (argumentSet("limit")) {
            int limit = getArgumentValue("limit", Integer.class);
            if (!(limit > 0 && limit <= 10)) {
                throw new InvalidCommandException("Limit must be between 1 and 10");
            }

            List<YouTubePlaylist> playlists = youTubeService.searchSeveralPlaylists(limit, getCommandBody());
            if (playlists.size() == 1) {
                audioPlayback.getAudioQueue().set(audioManager.createPlayables(playlists.get(0), audioPlayback));
            } else if (playlists.isEmpty()) {
                throw new NoResultsFoundException("No YouTube playlists found for " + getCommandBody());
            } else {
                askQuestion(playlists, YouTubePlaylist::getTitle, YouTubePlaylist::getChannelTitle);
            }
        } else {
            YouTubePlaylist youTubePlaylist = youTubeService.searchPlaylist(getCommandBody());
            audioPlayback.getAudioQueue().set(audioManager.createPlayables(youTubePlaylist, audioPlayback));
            audioManager.playTrack(guild, channel);
        }
    }

    private void playLocalList(VoiceChannel channel,
                               AudioManager audioManager,
                               Guild guild,
                               AudioPlayback audioPlayback) throws Exception {
        Playlist playlist = SearchEngine.searchLocalList(getPersistContext(), getCommandBody());
        if (playlist == null) {
            throw new InvalidCommandException("No local playlist found for '" + getCommandBody() + "'");
        }

        List<Object> items = runWithCredentials(() -> playlist.getItems(getManager().getSpotifyApi()));

        if (items.isEmpty()) {
            throw new NoResultsFoundException("Playlist is empty");
        }

        audioPlayback.getAudioQueue().set(audioManager.createPlayables(!argumentSet("preview"), items, audioPlayback));
        audioManager.playTrack(guild, channel);
    }

    private void playSpotifyList(VoiceChannel channel, AudioPlayback audioPlayback) throws Exception {
        AudioManager audioManager = getManager().getAudioManager();
        Guild guild = getContext().getGuild();
        MessageChannel communicationChannel = getContext().getChannel();
        SpotifyApi spotifyApi = getManager().getSpotifyApi();

        Callable<Void> callable = () -> {
            List<PlaylistSimplified> playlists;
            if (argumentSet("own")) {
                playlists = SearchEngine.searchOwnPlaylist(spotifyApi, getCommandBody());
            } else {
                playlists = SearchEngine.searchSpotifyPlaylist(spotifyApi, getCommandBody());
            }

            if (playlists.size() == 1) {
                PlaylistSimplified playlist = playlists.get(0);
                List<Track> tracks = SearchEngine.getPlaylistTracks(spotifyApi, playlist);

                if (tracks.isEmpty()) {
                    throw new NoResultsFoundException("Playlist " + playlist.getName() + " has no tracks");
                }

                audioPlayback.getAudioQueue().set(audioManager.createPlayables(!argumentSet("review"), tracks, audioPlayback));
                audioManager.playTrack(guild, channel);
            } else if (playlists.isEmpty()) {
                sendMessage(communicationChannel, "No results found");
            } else {
                askQuestion(playlists, PlaylistSimplified::getName, p -> p.getOwner().getDisplayName());
            }

            return null;
        };

        if (argumentSet("own")) {
            runWithLogin(getContext().getUser(), callable);
        } else {
            runWithCredentials(callable);
        }
    }

    @Override
    public void onSuccess() {
        // current track notification sent by AudioManager
    }

    @Override
    public void withUserResponse(Object chosenOption) throws Exception {
        AudioManager audioManager = getManager().getAudioManager();
        Guild guild = getContext().getGuild();
        AudioPlayback playback = audioManager.getPlaybackForGuild(guild);
        AudioQueue queue = playback.getAudioQueue();

        if (chosenOption instanceof Track || chosenOption instanceof YouTubeVideo) {
            queue.set(audioManager.createPlayable(!argumentSet("preview"), chosenOption));
        } else if (chosenOption instanceof PlaylistSimplified) {
            PlaylistSimplified playlist = (PlaylistSimplified) chosenOption;
            SpotifyApi spotifyApi = getManager().getSpotifyApi();
            List<Track> tracks = runWithCredentials(() -> SearchEngine.getPlaylistTracks(spotifyApi, playlist));
            queue.set(audioManager.createPlayables(!argumentSet("preview"), tracks, playback));
        } else if (chosenOption instanceof YouTubePlaylist) {
            YouTubePlaylist youTubePlaylist = (YouTubePlaylist) chosenOption;
            queue.set(audioManager.createPlayables(youTubePlaylist, playback));
        }

        Member member = guild.getMember(getContext().getUser());
        VoiceChannel channel = member.getVoiceState().getChannel();
        audioManager.playTrack(guild, channel);
    }

    @Override
    public ArgumentContribution setupArguments() {
        ArgumentContribution argumentContribution = new ArgumentContribution(this);
        argumentContribution.map("list").setRequiresInput(true)
            .setDescription("Search for a list.");
        argumentContribution.map("preview").excludesArguments("youtube")
            .setDescription("Play the short preview mp3 directly from spotify instead of the full track from youtube.");
        argumentContribution.map("spotify").setRequiresInput(true).excludesArguments("youtube")
            .setDescription("Play a spotify track or list.");
        argumentContribution.map("youtube").setRequiresInput(true).excludesArguments("spotify")
            .setDescription("Play a youtube video or playlist.");
        argumentContribution.map("own").needsArguments("spotify")
            .setDescription("Limit search to spotify tracks or lists that are in the current user's library.");
        argumentContribution.map("local").needsArguments("list")
            .setDescription("Play a local list.");
        argumentContribution.map("limit").needsArguments("youtube").setRequiresValue(true)
            .setDescription("Show a selection of youtube playlists or videos to chose from. Requires value from 1 to 10: $limit=5");
        return argumentContribution;
    }
}
