package com.lbz.f1aipredict.prediction.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lbz.f1aipredict.common.InvalidRequestException;
import com.lbz.f1aipredict.question.entity.Question;
import com.lbz.f1aipredict.question.entity.QuestionSnapshot;
import com.lbz.f1aipredict.question.mapper.QuestionMapper;
import com.lbz.f1aipredict.question.mapper.QuestionSnapshotMapper;
import com.lbz.f1aipredict.question.service.PredictionQuestionOptionView;
import com.lbz.f1aipredict.question.service.PredictionQuestionView;
import com.lbz.f1aipredict.season.dto.MeetingSessionDto;
import com.lbz.f1aipredict.season.dto.RoundDto;
import com.lbz.f1aipredict.season.entity.Season;
import com.lbz.f1aipredict.season.mapper.SeasonMapper;
import com.lbz.f1aipredict.season.service.MeetingSessionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

/** 验证快照冻结和多 Session 不猜测规则。 */
@ExtendWith(MockitoExtension.class)
class PredictionRequestSnapshotResolverTest {
    @Mock QuestionMapper questions;
    @Mock QuestionSnapshotMapper snapshots;
    @Mock SeasonMapper seasons;
    @Mock MeetingSessionService sessions;

    @Test
    void freeze_usesSelectedSnapshotAndNullsAmbiguousSession() {
        PredictionRequestSnapshotResolver resolver = new PredictionRequestSnapshotResolver(
                questions, snapshots, seasons, sessions, new ObjectMapper());
        RoundDto round = RoundDto.builder().id(7L).seasonId(1L).roundNumber(4)
                .circuitName("Silverstone").build();
        Season season = new Season();
        season.setYear(2026);
        when(seasons.selectById(1L)).thenReturn(season);
        Question current = new Question();
        current.setId(11L);
        current.setRoundId(7L);
        current.setGamedayId(19);
        current.setLatestSnapshotId(501L);
        current.setQuestionText("更新后的题目");
        when(questions.selectByIds(anyCollection())).thenReturn(List.of(current));
        QuestionSnapshot snapshot = new QuestionSnapshot();
        snapshot.setId(501L);
        snapshot.setQuestionId(11L);
        snapshot.setRawJson("{\"Text\":\"冻结题目\",\"SubText\":null,\"Config\":{\"ChoiceLimit\":2}}");
        when(snapshots.selectSnapshotByIds(anyCollection())).thenReturn(List.of(snapshot));
        when(sessions.listByRoundId(7L)).thenReturn(List.of(
                MeetingSessionDto.builder().gamedayId(19).meetingKey(1).sessionKey(2).build(),
                MeetingSessionDto.builder().gamedayId(19).meetingKey(1).sessionKey(3).build()));

        var frozen = resolver.freeze(round, List.of(new PredictionQuestionView(11L, 1, 501L,
                List.of(new PredictionQuestionOptionView(1, 0, "选项", null, null)))));

        assertThat(frozen.questions().get(11L).questionText()).isEqualTo("冻结题目");
        assertThat(frozen.questions().get(11L).choiceLimit()).isEqualTo(2);
        assertThat(frozen.questions().get(11L).meetingKey()).isEqualTo(1);
        assertThat(frozen.questions().get(11L).sessionKey()).isNull();
    }

    @Test
    void freeze_usesMeetingAndSessionKeysForSingleMatch() {
        var frozen = freezeWithSessions(List.of(MeetingSessionDto.builder()
                .gamedayId(19).meetingKey(7).sessionKey(8).build()));

        assertThat(frozen.questions().get(11L).meetingKey()).isEqualTo(7);
        assertThat(frozen.questions().get(11L).sessionKey()).isEqualTo(8);
    }

    @Test
    void freeze_rejectsMissingMeetingSessions() {
        assertThatThrownBy(() -> freezeWithSessions(List.of()))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void freeze_rejectsMissingMeetingKeyAmongMatches() {
        assertThatThrownBy(() -> freezeWithSessions(List.of(
                MeetingSessionDto.builder().gamedayId(19).meetingKey(7).sessionKey(8).build(),
                MeetingSessionDto.builder().gamedayId(19).meetingKey(null).sessionKey(9).build())))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void freeze_rejectsConflictingMeetingKeys() {
        assertThatThrownBy(() -> freezeWithSessions(List.of(
                MeetingSessionDto.builder().gamedayId(19).meetingKey(7).sessionKey(8).build(),
                MeetingSessionDto.builder().gamedayId(19).meetingKey(10).sessionKey(9).build())))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void freeze_rejectsMissingSessionKeyForSingleMatch() {
        assertThatThrownBy(() -> freezeWithSessions(List.of(MeetingSessionDto.builder()
                .gamedayId(19).meetingKey(7).sessionKey(null).build())))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void freeze_rejectsMissingSnapshotText() {
        PredictionRequestSnapshotResolver resolver = new PredictionRequestSnapshotResolver(
                questions, snapshots, seasons, sessions, new ObjectMapper());
        RoundDto round = RoundDto.builder().id(7L).seasonId(1L).roundNumber(4)
                .circuitName("Silverstone").build();
        Season season = new Season();
        season.setYear(2026);
        when(seasons.selectById(1L)).thenReturn(season);
        Question current = new Question();
        current.setId(11L);
        current.setRoundId(7L);
        current.setGamedayId(19);
        current.setLatestSnapshotId(501L);
        when(questions.selectByIds(anyCollection())).thenReturn(List.of(current));
        QuestionSnapshot snapshot = new QuestionSnapshot();
        snapshot.setId(501L);
        snapshot.setQuestionId(11L);
        snapshot.setRawJson("{}");
        when(snapshots.selectSnapshotByIds(anyCollection())).thenReturn(List.of(snapshot));
        when(sessions.listByRoundId(7L)).thenReturn(List.of(MeetingSessionDto.builder()
                .gamedayId(19).meetingKey(1).sessionKey(2).build()));

        assertThatThrownBy(() -> resolver.freeze(round, List.of(new PredictionQuestionView(
                11L, 1, 501L, List.of())))).isInstanceOf(InvalidRequestException.class);
    }

    private PredictionRequestSnapshotResolver.FrozenBatch freezeWithSessions(
            List<MeetingSessionDto> meetingSessions) {
        PredictionRequestSnapshotResolver resolver = new PredictionRequestSnapshotResolver(
                questions, snapshots, seasons, sessions, new ObjectMapper());
        RoundDto round = RoundDto.builder().id(7L).seasonId(1L).roundNumber(4)
                .circuitName("Silverstone").build();
        Season season = new Season();
        season.setYear(2026);
        when(seasons.selectById(1L)).thenReturn(season);
        Question current = new Question();
        current.setId(11L);
        current.setRoundId(7L);
        current.setGamedayId(19);
        current.setLatestSnapshotId(501L);
        when(questions.selectByIds(anyCollection())).thenReturn(List.of(current));
        QuestionSnapshot snapshot = new QuestionSnapshot();
        snapshot.setId(501L);
        snapshot.setQuestionId(11L);
        snapshot.setRawJson("{\"Text\":\"冻结题目\",\"Config\":{\"ChoiceLimit\":2}}");
        when(snapshots.selectSnapshotByIds(anyCollection())).thenReturn(List.of(snapshot));
        when(sessions.listByRoundId(7L)).thenReturn(meetingSessions);

        return resolver.freeze(round, List.of(new PredictionQuestionView(
                11L, 1, 501L, List.of())));
    }
}
